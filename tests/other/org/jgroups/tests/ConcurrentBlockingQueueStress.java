package org.jgroups.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.function.Supplier;

import org.jgroups.util.ConcurrentBlockingRingBuffer;
import org.jgroups.util.ConcurrentLinkedBlockingQueue;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

public class ConcurrentBlockingQueueStress {

   private static final Supplier<BlockingQueue<Integer>> RING_BUFFER_FALSE_FALSE = () ->
         new ConcurrentBlockingRingBuffer<>(4, false, false);

   private static final Supplier<BlockingQueue<Integer>> RING_BUFFER_TRUE_FALSE = () ->
         new ConcurrentBlockingRingBuffer<>(4, true, false);

   private static final Supplier<BlockingQueue<Integer>> QUEUE_FALSE_FALSE = () ->
         new ConcurrentLinkedBlockingQueue<>(4, false, false);

   private static final Supplier<BlockingQueue<Integer>> QUEUE_TRUE_FALSE = () ->
         new ConcurrentLinkedBlockingQueue<>(4, true, false);

   private static final Supplier<BlockingQueue<Integer>> POLL_QUEUE;
   private static final Supplier<BlockingQueue<Integer>> TAKE_QUEUE;

   static {
      // Use environment variables to choose which queue to use.
      // rb -> ring buffer, the default.
      // bq -> blocking queue.
      String mode = System.getProperty("org.jgroups.jcstress.mode", "rb");
      if ("bq".equals(mode)) {
         System.out.println("\nUtilizing concurrent blocking queue\n");
         POLL_QUEUE = QUEUE_FALSE_FALSE;
         TAKE_QUEUE = QUEUE_TRUE_FALSE;
      } else {
         System.out.println("\nUtilizing ring buffer\n");
         POLL_QUEUE = RING_BUFFER_FALSE_FALSE;
         TAKE_QUEUE = RING_BUFFER_TRUE_FALSE;
      }
   }

   @State
   @JCStressTest
   @Outcome(id = {"1", "2"}, expect = Expect.ACCEPTABLE, desc = "Publish and Consume")
   public static class OfferPollTest {
      private final BlockingQueue<Integer> buffer;

      public OfferPollTest() {
         this.buffer = POLL_QUEUE.get();

         // Poll won't block, we publish an element first.
         boolean b = buffer.offer(1);
         if (!b) throw new IllegalStateException("Not offered");
      }

      @Actor
      public void publish1() {
         boolean b = buffer.offer(1);
         if (!b) throw new IllegalStateException("Not offered");
      }

      @Actor
      public void actorPublish42() {
         boolean b = buffer.offer(42);
         if (!b) throw new IllegalStateException("Not published");
      }

      @Actor
      public void actorConsume() {
         Integer polled = buffer.poll();
         if (polled != null && polled != 1 && polled != 42)
            throw new IllegalStateException("Incorrect polled: " + polled);
      }

      @Arbiter
      public void arbiter(I_Result result) {
         result.r1 = buffer.size();
      }
   }

   @State
   @JCStressTest
   @Outcome(id = {"1, 42", "42, 1"}, expect = Expect.ACCEPTABLE, desc = "Multi publish")
   public static class ConcurrentPollTest {
      private final BlockingQueue<Integer> buffer = POLL_QUEUE.get();

      @Actor
      public void publish1() {
         boolean b = buffer.offer(1);
         if (!b) throw new IllegalStateException("Not offered");
      }

      @Actor
      public void publish42() {
         boolean b = buffer.offer(42);
         if (!b) throw new IllegalStateException("Not offered");
      }

      @Arbiter
      public void arbiter(II_Result result) {
         result.r1 = Objects.requireNonNull(buffer.poll());
         result.r2 = Objects.requireNonNull(buffer.poll());
      }
   }

   @State
   @JCStressTest
   @Outcome(id = {"1, 1", "1, 42"}, expect = Expect.ACCEPTABLE, desc = "Publish and blocking take")
   public static class OfferTakeTest {
      private final BlockingQueue<Integer> buffer = TAKE_QUEUE.get();

      @Actor
      public void publish1() {
         boolean b = buffer.offer(1);
         if (!b) throw new IllegalStateException("Not offered");
      }

      @Actor
      public void publish42() {
         boolean b = buffer.offer(42);
         if (!b) throw new IllegalStateException("Not offered");
      }

      @Actor
      public void actorConsume() {
         try {
            // Take will always block until it has an element.
            int value = Objects.requireNonNull(buffer.take());
            if (value != 1 && value != 42)
               throw new IllegalStateException("Incorrect take: " + value);
         } catch (InterruptedException e) {
            throw new RuntimeException(e);
         }
      }

      @Arbiter
      public void arbiter(II_Result result) {
         result.r1 = buffer.size();
         result.r2 = Objects.requireNonNull(buffer.poll());
      }
   }

   @State
   @JCStressTest
   @Outcome(id = {"0, 0", "1, 1", "1, 42", "2, 43"}, expect = Expect.ACCEPTABLE, desc = "Publish and drain")
   public static class DrainToOfferTest {
      private final BlockingQueue<Integer> buffer = POLL_QUEUE.get();;
      private final List<Integer> output = new ArrayList<>();

      @Actor
      public void publish1() {
         boolean b = buffer.offer(1);
         if (!b) throw new IllegalStateException("Not offered");
      }

      @Actor
      public void publish42() {
         boolean b = buffer.offer(42);
         if (!b) throw new IllegalStateException("Not offered");
      }

      @Actor
      public void drainTo() {
         buffer.drainTo(output);
      }

      @Arbiter
      public void arbiter(II_Result result) {
         result.r1 = output.size();
         result.r2 = output.stream().reduce(0, Integer::sum);
      }
   }
}
