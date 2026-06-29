package org.jgroups.util.concurrent;

import java.io.DataOutput;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.jgroups.util.RegionDataOutput;

abstract class RingBufferPad1 {
   int i01;
   long p01, p02, p03, p04, p05, p06;
}

abstract class RingBufferFields extends RingBufferPad1 {
   protected final byte[] buffer;
   protected final int capacity;
   protected final int mask;

   // Contended on the producers
   protected volatile long tail;

   protected RingBufferFields(int capacity) {
      if (Integer.bitCount(capacity) != 1)
         throw new IllegalArgumentException("Capacity must be a power of 2, it was " + capacity);

      this.capacity = capacity;
      this.mask = capacity - 1;
      this.buffer = new byte[capacity];
   }
}

abstract class RingBufferPad2 extends RingBufferFields {
   long p21, p22, p23, p24, p25, p26, p27, p28;

   protected RingBufferPad2(int capacity) {
      super(capacity);
   }
}

abstract class RingBufferHeadCache extends RingBufferPad2 {

   protected long headCache;

   protected RingBufferHeadCache(int capacity) {
      super(capacity);
   }
}

abstract class RingBufferPad3 extends RingBufferHeadCache {
   long p31, p32, p33, p34, p35, p36, p37, p38;

   protected RingBufferPad3(int capacity) {
      super(capacity);
   }
}

abstract class RingBufferHead extends RingBufferPad3 {

   // Updated by the single consumer.
   // Padded so it stays in a different cache line than the tail variable.
   protected volatile long head;

   protected RingBufferHead(int capacity) {
      super(capacity);
   }
}

abstract class RingBufferPad4 extends RingBufferHead {
   long p41, p42, p43, p44, p45, p46, p47, p48;

   protected RingBufferPad4(int capacity) {
      super(capacity);
   }
}

final class DefaultMessageRingBuffer extends RingBufferPad4 implements MessageRingBuffer {

   private static final int ALIGNMENT = 8;
   private static final int HEADER_LENGTH = 4;
   private static final int PADDING_MSG = Integer.MIN_VALUE;

   private static final VarHandle TAIL;
   private static final VarHandle HEAD;
   private static final VarHandle BUFFER_INT_VIEW;

   static {
      try {
         MethodHandles.Lookup l = MethodHandles.lookup();
         TAIL = l.findVarHandle(RingBufferFields.class, "tail", long.class);
         HEAD = l.findVarHandle(RingBufferHead.class, "head", long.class);
         // View byte[] as int[] to write 4-byte header updates.
         BUFFER_INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
      } catch (NoSuchFieldException | IllegalAccessException e) {
         throw new ExceptionInInitializerError(e);
      }
   }

   private final ThreadLocal<ClaimImpl> localClaim = ThreadLocal.withInitial(ClaimImpl::new);

   DefaultMessageRingBuffer(int capacity) {
      super(capacity);
   }

   @Override
   public int capacity() {
      return capacity;
   }

   @Override
   public Claim claim(int length) {
      int recordSize = align(length + HEADER_LENGTH, ALIGNMENT);
      if (recordSize > capacity)
         throw new IllegalArgumentException("Record exceeds buffer capacity: " + recordSize + " > " + capacity);

      if (length <= 0)
         throw new IllegalArgumentException("Length must be positive, was " + length);

      long currentTail;
      long newTail;
      int padding;
      int index;
      int spins = 0;

      while (true) {
         if (Thread.currentThread().isInterrupted())
            return null;

         currentTail = tail;
         index = (int) (currentTail & mask);
         int availableToEnd = capacity - index;
         padding = 0;

         if (recordSize > availableToEnd) {
            padding = availableToEnd;
         }

         int requiredSpace = padding + recordSize;
         long usedCapacity = currentTail - headCache;

         if (requiredSpace > capacity - usedCapacity) {
            headCache = head;
            usedCapacity = currentTail - headCache;
            if (requiredSpace > capacity - usedCapacity) {
               spins = applyBackpressure(spins);
               continue;
            }
         }

         newTail = currentTail + requiredSpace;
         if (TAIL.compareAndSet(this, currentTail, newTail))
            break;

         spins = applyBackpressure(spins);
      }

      if (padding > 0) {
         VarHandle.releaseFence();
         BUFFER_INT_VIEW.set(buffer, index, PADDING_MSG);
         index = 0;
      }

      BUFFER_INT_VIEW.set(buffer, index, -length);

      ClaimImpl claim = localClaim.get();
      claim.wrap(this, index, length);
      return claim;
   }

   @Override
   public int read(RecordHandler handler) {
      int readCount = 0;
      long currentHead = head;
      long maxHead = tail;

      while (currentHead < maxHead) {
         int index = (int) (currentHead & mask);

         // Acquire semantics establishes a happens-before with the producer side on commitRecord.
         int length = (int) BUFFER_INT_VIEW.get(buffer, index);
         VarHandle.acquireFence();

         // Padding detected with the placeholder.
         if (length == PADDING_MSG) {
            int paddingLen = capacity - index;
            Arrays.fill(buffer, index, capacity, (byte) 0);
            currentHead += paddingLen;
            continue;
         }

         // A negative value means there is work-in-progress at the producer side.
         // We exit earlier and wait to trigger read again by consumer.
         if (length <= 0) {
            break;
         }

         handler.onRecord(buffer, index + HEADER_LENGTH, length);

         // Clean up previous data.
         int recordSize = align(length + HEADER_LENGTH, ALIGNMENT);
         Arrays.fill(buffer, index, index + recordSize, (byte) 0);

         currentHead += recordSize;
         readCount++;
      }

      if (currentHead > head) {
         // Use release so producers can see the new free spaces.
         HEAD.setRelease(this, currentHead);
      }
      return readCount;
   }

   private static int align(int value, int alignment) {
      return (value + (alignment - 1)) & ~(alignment - 1);
   }

   private int applyBackpressure(int spins) {
      if (spins < 7) {
         Thread.onSpinWait();
      } else {
         Thread.yield();
      }
      return spins + 1;
   }

   private void commitRecord(int index, int recordSize) {
      // Flip the negative value to indicate the entry is ready for consumption.
      VarHandle.releaseFence();
      BUFFER_INT_VIEW.set(buffer, index, recordSize);
   }

   private static final class ClaimImpl implements MessageRingBuffer.Claim {
      private final RegionDataOutput output = new RegionDataOutput(null, 0, 0);
      private DefaultMessageRingBuffer parent;
      private int recordIndex;
      private int recordSize;

      void wrap(DefaultMessageRingBuffer parent, int recordIndex, int length) {
         this.parent = parent;
         this.recordIndex = recordIndex;
         this.recordSize = length;
         this.output.reset(parent.buffer, recordIndex + HEADER_LENGTH, length);
      }

      @Override
      public DataOutput output() {
         return output;
      }

      @Override
      public void commit() {
         parent.commitRecord(recordIndex, recordSize);
      }
   }
}
