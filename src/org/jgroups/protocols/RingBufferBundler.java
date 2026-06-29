package org.jgroups.protocols;

import java.io.DataOutput;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.jgroups.Address;
import org.jgroups.Message;
import org.jgroups.NullAddress;
import org.jgroups.View;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.conf.AttributeType;
import org.jgroups.util.Bits;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.Runner;
import org.jgroups.util.Util;
import org.jgroups.util.concurrent.MessageRingBuffer;

public class RingBufferBundler extends BaseBundler implements Runnable, MessageRingBuffer.RecordHandler {

   private static final VarHandle MSGS;
   private static final Address   NULL_KEY = new NullAddress();
   private static final int       RB_CAPACITY   = 1 << 21; // 2 MB per destination
   private static final int       SENDER_SPINS  = 128;

   static {
      try {
         MSGS = MethodHandles.lookup()
            .findVarHandle(RingBufferBundler.class, "msgsAvailable", int.class);
      } catch (ReflectiveOperationException e) {
         throw new ExceptionInInitializerError(e);
      }
   }

   private Address                       localAddress;
   private final Map<Address, DestEntry> destinations = Util.createConcurrentMap();
   private volatile DestEntry[]          destSnapshot = new DestEntry[0];
   private Runner                        senderRunner;
   private volatile int                  msgsAvailable;

   // Sender-thread-only fields — no synchronization needed
   private DestEntry senderCurrentDest;
   private int       senderCountPos;
   private int       senderBatchCount;
   private int       senderBatchSize;

   // ---- Lifecycle ----

   @Override
   public void init(TP transport) {
      super.init(transport);
      if (transport instanceof TCP tcp) {
         int bufSize = tcp.getBufferedOutputStreamSize();
         if (bufSize < max_size) {
            int adjusted = max_size + Integer.BYTES;
            log.warn("buffered_output_stream_size adjusted from %,d -> %,d", bufSize, adjusted);
            tcp.setBufferedOutputStreamSize(adjusted);
         }
      }
   }

   @Override
   public void start() {
      super.start();
      localAddress = Objects.requireNonNull(transport.getAddress());
      senderRunner = new Runner(transport.getThreadFactory(), "rb-bundler", this, null)
         .joinTimeout(0);
      senderRunner.start();
   }

   @Override
   public void stop() {
      Util.close(senderRunner);
      super.stop();
      destinations.clear();
      destSnapshot = new DestEntry[0];
      msgsAvailable = 0;
   }

   @ManagedAttribute(description = "Whether the sender thread is running")
   public boolean isRunning() {
      return senderRunner != null && senderRunner.isRunning();
   }

   @ManagedAttribute(description = "Number of unsent messages across all ring buffers",
      type = AttributeType.SCALAR, gauge = true)
   @Override
   public int size() {
      return msgsAvailable;
   }

   @Override
   public int getQueueSize() {
      return -1;
   }

   // ---- Producer path (caller threads) ----

   @Override
   public void send(Message msg) throws Exception {
      if (msg.getSrc() == null)
         msg.setSrc(localAddress);

      Address dest = msg.getDest();
      if (dest == null) {
         loopbackUnlessDontLoopbackIsSet(msg);
         writeToRingBuffer(NULL_KEY, msg);
         return;
      }

      if (Objects.equals(localAddress, dest)) {
         loopbackUnlessDontLoopbackIsSet(msg);
         return;
      }

      writeToRingBuffer(dest, msg);
   }

   private void writeToRingBuffer(Address key, Message msg) throws Exception {
      DestEntry de = destinations.get(key);
      if (de == null)
         de = getOrCreateDest(key);

      int bodyLen = Short.BYTES + msg.sizeNoAddrs(localAddress);
      MessageRingBuffer.Claim claim = de.rb.claim(bodyLen);
      if (claim == null)
         return;

      DataOutput out = claim.output();
      out.writeShort(msg.getType());
      msg.writeToNoAddrs(localAddress, out);
      claim.commit();

      if ((int) MSGS.getAndAdd(this, 1) == 0) {
         Thread t = senderRunner.getThread();
         if (t != null)
            LockSupport.unpark(t);
      }
   }

   private DestEntry getOrCreateDest(Address key) {
      Address dest = key == NULL_KEY ? null : key;
      boolean multicast = dest == null;

      ByteArrayDataOutputStream hdr = new ByteArrayDataOutputStream(64);
      try {
         Util.writeMessageListHeader(dest, localAddress,
            transport.getClusterNameAscii().val(), 0, hdr, multicast);
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
      byte[] headerBytes = hdr.buffer();
      DestEntry de = new DestEntry(dest, MessageRingBuffer.create(RB_CAPACITY), headerBytes, hdr.position());
      DestEntry existing = destinations.putIfAbsent(key, de);
      if (existing != null)
         return existing;

      destSnapshot = destinations.values().toArray(new DestEntry[0]);
      return de;
   }

   // ---- Sender thread ----

   @Override
   public void run() {
      int processed = drainAll();
      if (processed > 0) {
         MSGS.getAndAdd(this, -processed);
         return;
      }

      for (int i = 0; i < SENDER_SPINS && msgsAvailable == 0; i++)
         Thread.onSpinWait();

      processed = drainAll();
      if (processed > 0) {
         MSGS.getAndAdd(this, -processed);
         return;
      }

      if (msgsAvailable == 0)
         LockSupport.park();
   }

   private int drainAll() {
      int total = 0;
      DestEntry[] snapshot = destSnapshot;
      for (DestEntry destEntry : snapshot)
         total += drainDest(destEntry);
      return total;
   }

   private int drainDest(DestEntry de) {
      senderCurrentDest = de;
      senderBatchCount = 0;
      senderBatchSize = 0;

      output.position(0);
      output.write(de.headerBytes, 0, de.length);
      senderCountPos = de.length - Integer.BYTES;

      try {
         int totalRead = de.rb.read(this);
         if (senderBatchCount > 0)
            flush();
         return totalRead;
      } catch (Exception e) {
         logFailure(de.dest, e);
      }
      return 0;
   }

   private void flush() {
      try {
         Bits.writeInt(senderBatchCount, output.buffer(), senderCountPos);
         transport.doSend(output.buffer(), 0, output.position(), senderCurrentDest.dest);
         total_msgs_sent.add(senderBatchCount);
         num_batches_sent.increment();
      } catch (Exception e) {
         logFailure(senderCurrentDest.dest, e);
      }
   }

   @Override
   public void onRecord(byte[] buffer, int offset, int length) {
      if (senderBatchSize + length >= max_size && senderBatchCount > 0) {
         flush();

         DestEntry de = senderCurrentDest;
         output.position(0);
         output.write(de.headerBytes, 0, de.length);
         senderBatchCount = 0;
         senderBatchSize = 0;
      }

      output.write(buffer, offset, length);
      senderBatchCount++;
      senderBatchSize += length;
   }

   // ---- View changes ----

   @Override
   public void viewChange(View view) {
      List<Address> left = Util.leftMembers(this.members, view.getMembers());
      super.viewChange(view);
      if (left != null && !left.isEmpty()) {
         transport.getTimer().schedule(() -> {
            left.forEach(destinations::remove);
            destSnapshot = destinations.values().toArray(new DestEntry[0]);
         }, remove_delay, TimeUnit.MILLISECONDS);
      }
   }

   // ---- Error handling ----

   private void logFailure(Address dest, Exception e) {
      if (suppress_log_timeout <= 0)
         log.trace(FMT, transport.getAddress(), dest, e.getMessage());
      else
         suppress_log.warn(dest, suppress_log_timeout, FMT,
            transport.getAddress(), dest, e.getMessage());
   }

   // ---- Per-destination state ----

   private record DestEntry(Address dest, MessageRingBuffer rb, byte[] headerBytes, int length) {}
}
