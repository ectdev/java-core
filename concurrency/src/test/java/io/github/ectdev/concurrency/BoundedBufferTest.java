package io.github.ectdev.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedBufferTest {

    private static final Duration LIMIT = Duration.ofSeconds(60);

    // ------------------------------------------------------------------ single-threaded behaviour

    @Test
    void takesElementsInTheOrderTheyWerePut() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(3);
        buffer.put("a");
        buffer.put("b");
        buffer.put("c");
        assertEquals(3, buffer.size());
        assertEquals("a", buffer.take());
        assertEquals("b", buffer.take());
        assertEquals("c", buffer.take());
        assertEquals(0, buffer.size());
    }

    @Test
    void theCircularArrayWrapsAroundWithoutLosingOrder() throws InterruptedException {
        BoundedBuffer<Integer> buffer = new BoundedBuffer<>(3);
        int next = 0;
        int expected = 0;
        for (int round = 0; round < 100; round++) {
            // Alternate how full it is, so head and tail keep crossing the end of the array.
            int fill = 1 + round % 3;
            for (int i = 0; i < fill; i++) {
                buffer.put(next++);
            }
            for (int i = 0; i < fill; i++) {
                assertEquals(expected++, buffer.take());
            }
        }
    }

    @Test
    void aTakenElementIsNotKeptReachableFromTheBuffer() throws InterruptedException {
        BoundedBuffer<Object> buffer = new BoundedBuffer<>(3);
        buffer.put(new Object());
        buffer.put("second");
        buffer.take();
        assertNull(buffer.rawSlot(0), "the slot of the element that was taken");
        assertEquals("second", buffer.rawSlot(1));
    }

    @Test
    void offerOnAFullBufferTimesOutAndLeavesItUntouched() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(1);
        buffer.put("only");
        long started = System.nanoTime();
        assertFalse(buffer.offer("rejected", 50, TimeUnit.MILLISECONDS));
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(50),
            "it must really wait out the timeout, not give up early");
        assertEquals(1, buffer.size());
        assertEquals("only", buffer.take());
    }

    @Test
    void pollOnAnEmptyBufferTimesOutWithNull() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(2);
        long started = System.nanoTime();
        assertNull(buffer.poll(50, TimeUnit.MILLISECONDS));
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(50));
    }

    @Test
    void aZeroOrNegativeTimeoutNeverWaitsButStillTakesWhatIsThere() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(1);
        assertNull(buffer.poll(0, TimeUnit.SECONDS));
        assertTrue(buffer.offer("x", 0, TimeUnit.SECONDS), "room is there: a zero timeout is a try-once, not a refusal");
        assertFalse(buffer.offer("y", -5, TimeUnit.SECONDS));
        assertEquals("x", buffer.poll(-5, TimeUnit.SECONDS), "the element is there: the condition is tested before the clock");
    }

    @Test
    void nullElementsAreRejectedBecausePollUsesNullForTimeout() {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(1);
        assertThrows(NullPointerException.class, () -> buffer.put(null));
        assertThrows(NullPointerException.class, () -> buffer.offer(null, 1, TimeUnit.SECONDS));
        assertEquals(0, buffer.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void capacityMustBePositive(int capacity) {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBuffer<String>(capacity));
    }

    // ------------------------------------------------------------------ blocking

    @Test
    void aProducerBlocksOnAFullBufferUntilAConsumerMakesRoom() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(1);
        buffer.put("first");
        AtomicBoolean putReturned = new AtomicBoolean();
        Thread producer = new Thread(() -> {
            try {
                buffer.put("second");
                putReturned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "producer");
        producer.start();

        Threads.awaitBlocked(producer);
        assertFalse(putReturned.get(), "put must not return while the buffer is full");
        assertEquals("first", buffer.take()); // makes room: wakes the producer
        Threads.awaitTerminated(producer);
        assertTrue(putReturned.get());
        assertEquals("second", buffer.take());
    }

    @Test
    void aConsumerBlocksOnAnEmptyBufferUntilAProducerArrives() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(2);
        AtomicReference<String> received = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                received.set(buffer.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "consumer");
        consumer.start();

        Threads.awaitBlocked(consumer);
        assertNull(received.get());
        buffer.put("hello");
        Threads.awaitTerminated(consumer);
        assertEquals("hello", received.get());
    }

    @Test
    void aTimedOfferSucceedsIfRoomAppearsBeforeTheTimeoutEnds() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(1);
        buffer.put("first");
        AtomicReference<Boolean> offered = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                offered.set(buffer.offer("second", 60, TimeUnit.SECONDS)); // a timeout far longer than the test
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "timed-producer");
        producer.start();

        Threads.awaitBlocked(producer);
        assertNull(offered.get(), "still waiting: there is no room yet");
        long started = System.nanoTime();
        assertEquals("first", buffer.take());
        Threads.awaitTerminated(producer);
        assertTrue(offered.get(), "room appeared in time, so the offer must report success");
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30),
            "it must wake on the signal, not sit out its sixty-second timeout");
        assertEquals("second", buffer.take());
    }

    @Test
    void interruptingABlockedTakeThrowsAndLeavesTheBufferIntact() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(2);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlagAfter = new AtomicBoolean(true);
        Thread consumer = new Thread(() -> {
            try {
                buffer.take();
            } catch (InterruptedException e) {
                thrown.set(e);
                interruptFlagAfter.set(Thread.currentThread().isInterrupted());
            }
        }, "interrupted-consumer");
        consumer.start();
        Threads.awaitBlocked(consumer);
        consumer.interrupt();
        Threads.awaitTerminated(consumer);

        assertTrue(thrown.get() instanceof InterruptedException);
        assertFalse(interruptFlagAfter.get(), "InterruptedException clears the flag, as the contract says");
        // and the buffer still works: the interrupted waiter did not swallow a slot or a signal
        buffer.put("x");
        assertEquals("x", buffer.take());
    }

    @Test
    void interruptingABlockedPutThrowsAndTheElementIsNotAdded() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(1);
        buffer.put("occupying the only slot");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                buffer.put("never accepted");
            } catch (InterruptedException e) {
                thrown.set(e);
            }
        }, "interrupted-producer");
        producer.start();
        Threads.awaitBlocked(producer);
        producer.interrupt();
        Threads.awaitTerminated(producer);

        assertTrue(thrown.get() instanceof InterruptedException, "a producer blocked in put must respond to interruption");
        assertEquals(1, buffer.size());
        assertEquals("occupying the only slot", buffer.take());
        assertNull(buffer.poll(10, TimeUnit.MILLISECONDS), "the interrupted put must not have left an element behind");
    }

    @Test
    void anInterruptedWaiterDoesNotStealTheWakeupFromTheNextOne() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(1);
        AtomicReference<String> received = new AtomicReference<>();
        Thread doomed = new Thread(() -> {
            try {
                buffer.take();
            } catch (InterruptedException expected) {
                // interrupted on purpose
            }
        }, "doomed");
        Thread survivor = new Thread(() -> {
            try {
                received.set(buffer.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "survivor");
        doomed.start();
        Threads.awaitBlocked(doomed);
        survivor.start();
        Threads.awaitBlocked(survivor);

        doomed.interrupt();
        Threads.awaitTerminated(doomed);
        buffer.put("for-the-survivor");
        Threads.awaitTerminated(survivor);
        assertEquals("for-the-survivor", received.get());
    }

    @Test
    void anInterruptedTimedWaitReportsTheInterruptNotATimeout() throws InterruptedException {
        BoundedBuffer<String> buffer = new BoundedBuffer<>(1);
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                outcome.set(buffer.poll(60, TimeUnit.SECONDS) == null ? "timeout" : "value");
            } catch (InterruptedException e) {
                outcome.set("interrupted");
            }
        }, "timed-waiter");
        waiter.start();
        Threads.awaitBlocked(waiter);
        waiter.interrupt();
        Threads.awaitTerminated(waiter);
        assertEquals("interrupted", outcome.get());
    }

    // ------------------------------------------------------------------ many threads

    /**
     * The property that matters: with several producers and several consumers hammering a tiny buffer, every
     * element put is taken exactly once, none is invented, and each consumer sees any one producer's elements
     * in the order that producer put them. The buffer is deliberately much smaller than the traffic, so both
     * sides spend most of the run waiting on each other.
     */
    @Test
    void everyElementIsDeliveredExactlyOnceAndEachProducersOrderIsKept() {
        final int producers = 4;
        final int consumers = 4;
        final int perProducer = 5_000;
        final int perConsumer = producers * perProducer / consumers;

        assertTimeoutPreemptively(LIMIT, () -> {
            BoundedBuffer<long[]> buffer = new BoundedBuffer<>(3);
            ExecutorService executor = Executors.newFixedThreadPool(producers + consumers);
            try {
                List<Future<?>> work = new ArrayList<>();
                for (int p = 0; p < producers; p++) {
                    final int producer = p;
                    work.add(executor.submit(() -> {
                        for (int sequence = 0; sequence < perProducer; sequence++) {
                            buffer.put(new long[] {producer, sequence});
                        }
                        return null;
                    }));
                }
                List<Future<List<long[]>>> taken = new ArrayList<>();
                for (int c = 0; c < consumers; c++) {
                    taken.add(executor.submit(() -> {
                        List<long[]> mine = new ArrayList<>();
                        for (int i = 0; i < perConsumer; i++) {
                            mine.add(buffer.take());
                        }
                        return mine;
                    }));
                }
                for (Future<?> f : work) {
                    f.get();
                }

                Set<Long> seen = new HashSet<>();
                for (Future<List<long[]>> future : taken) {
                    long[] lastSequence = new long[producers];
                    Arrays.fill(lastSequence, -1);
                    for (long[] element : future.get()) {
                        int producer = (int) element[0];
                        long sequence = element[1];
                        assertTrue(seen.add(producer * 1_000_000L + sequence),
                            "element " + producer + ":" + sequence + " was delivered twice");
                        assertTrue(sequence > lastSequence[producer],
                            "a consumer saw producer " + producer + "'s elements out of order");
                        lastSequence[producer] = sequence;
                    }
                }
                assertEquals(producers * perProducer, seen.size(), "an element was lost");
                assertEquals(0, buffer.size());
            } finally {
                executor.shutdownNow();
            }
        });
    }

    @Test
    void aSingleSlotBufferHandsOverLargeTrafficWithoutDeadlock() {
        assertTimeoutPreemptively(LIMIT, () -> {
            BoundedBuffer<Integer> buffer = new BoundedBuffer<>(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> producer = executor.submit(() -> {
                    for (int i = 0; i < 50_000; i++) {
                        buffer.put(i);
                    }
                    return null;
                });
                Future<Long> consumer = executor.submit(() -> {
                    long sum = 0;
                    for (int i = 0; i < 50_000; i++) {
                        sum += buffer.take();
                    }
                    return sum;
                });
                producer.get();
                assertEquals(50_000L * 49_999L / 2, consumer.get(), "the sum of 0..49999");
            } finally {
                executor.shutdownNow();
            }
        });
    }
}
