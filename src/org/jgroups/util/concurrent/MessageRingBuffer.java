package org.jgroups.util.concurrent;

import java.io.DataOutput;

public interface MessageRingBuffer {

   static MessageRingBuffer create(int size) {
      return new DefaultMessageRingBuffer(size);
   }

   int capacity();

   /**
    * Claims a region in the ring buffer for the given body length.
    * Blocks with spin-then-yield backpressure if the buffer is full.
    *
    * @param length number of bytes the caller will write into the claimed region
    * @return a Claim providing a DataOutput for writing and a commit operation
    */
   Claim claim(int length);

   /**
    * Reads all contiguous committed records from the head, invoking the handler
    * for each record. Advances the head and reclaims space after reading.
    *
    * @param handler callback invoked for each committed record
    * @return number of records read
    */
   int read(RecordHandler handler);

   interface Claim {
      /**
       * @return a DataOutput positioned at the start of the claimed body region
       */
      DataOutput output();

      /**
       * Commits the record, making it visible to the consumer.
       * Must be called exactly once after writing the body.
       */
      void commit();
   }

   @FunctionalInterface
   interface RecordHandler {

      /**
       * @param buffer the ring buffer's backing array
       * @param offset start of the record body
       * @param length number of body bytes
       */
      void onRecord(byte[] buffer, int offset, int length);
   }
}
