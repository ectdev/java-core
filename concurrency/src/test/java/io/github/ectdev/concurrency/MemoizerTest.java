package io.github.ectdev.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MemoizerTest {

    private static final Duration LIMIT = Duration.ofSeconds(60);

    /** What the tests need from "something that looks values up": the real memoizer and a deliberately broken one. */
    private interface Lookup<K, V> {
        V get(K key) throws InterruptedException;
    }

    /**
     * The textbook mistake: a concurrent map used with check-then-act. Every call on the map is atomic and the
     * sequence is not, so two threads can both see the key missing and both compute. It exists only so that the
     * race harness below can be shown to catch a real bug; if the harness could not fail, a passing run of the
     * real memoizer would prove nothing.
     */
    private static final class NaiveMemoizer<K, V> implements Lookup<K, V> {
        private final Map<K, V> cache = new ConcurrentHashMap<>();
        private final Function<K, V> compute;

        NaiveMemoizer(Function<K, V> compute) {
            this.compute = compute;
        }

        @Override
        public V get(K key) {
            V value = cache.get(key);
            if (value == null) {
                value = compute.apply(key);
                cache.put(key, value);
            }
            return value;
        }
    }

    /**
     * Starts {@code threads} callers on the same key at once and returns what each got. The computation inside
     * {@code compute} calls {@link #pause}; see there.
     */
    private static <V> List<V> race(int threads, Lookup<String, V> lookup) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<V>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return lookup.get("key");
                }));
            }
            ready.await();
            go.countDown();
            List<V> values = new ArrayList<>();
            for (Future<V> result : results) {
                values.add(result.get(30, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Called from inside a computation to hold it open. If a second thread starts computing the same key, it
     * announces itself by raising {@code concurrent}, and both are released at once: so a memoizer with the race
     * is caught deterministically, instead of depending on lucky timing. A correct memoizer never lets a second
     * thread in, so there the pause simply runs out the clock.
     */
    private static void pause(AtomicInteger concurrent, long maxMillis) {
        concurrent.incrementAndGet();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillis);
        while (concurrent.get() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    // ------------------------------------------------------------------ sequential behaviour

    @Test
    void computesEachKeyOnceAndReturnsTheCachedValueAfterwards() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        Memoizer<Integer, String> memo = new Memoizer<>(k -> {
            calls.incrementAndGet();
            return "value-" + k;
        });
        assertEquals("value-1", memo.get(1));
        assertEquals("value-1", memo.get(1));
        assertEquals("value-2", memo.get(2));
        assertEquals("value-1", memo.get(1));
        assertEquals(2, calls.get(), "one computation per distinct key");
        assertEquals(2, memo.size());
    }

    @Test
    void theSameInstanceIsReturnedEveryTime() throws InterruptedException {
        Memoizer<String, Object> memo = new Memoizer<>(k -> new Object());
        assertSame(memo.get("a"), memo.get("a"));
    }

    @Test
    void aFailedComputationIsNotRememberedAndTheNextCallerRetries() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        Memoizer<String, String> memo = new Memoizer<>(k -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("transient failure");
            }
            return "ok";
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> memo.get("k"));
        assertEquals("transient failure", failure.getMessage(), "the original exception, not a wrapper");
        assertEquals(0, memo.size(), "a failure leaves nothing behind");
        assertEquals("ok", memo.get("k"));
        assertEquals("ok", memo.get("k"));
        assertEquals(2, calls.get());
    }

    @Test
    void errorsPropagateToo() {
        Memoizer<String, String> memo = new Memoizer<>(k -> {
            throw new AssertionError("an Error, not an Exception");
        });
        assertThrows(AssertionError.class, () -> memo.get("k"));
        assertEquals(0, memo.size());
    }

    @Test
    void aNullResultIsAFailureAndIsNotCached() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        Memoizer<String, String> memo = new Memoizer<>(k -> calls.incrementAndGet() == 1 ? null : "late");
        assertThrows(NullPointerException.class, () -> memo.get("k"));
        assertEquals("late", memo.get("k"));
    }

    @Test
    void nullKeysAndNullFunctionsAreRejected() {
        assertThrows(NullPointerException.class, () -> new Memoizer<String, String>(null));
        Memoizer<String, String> memo = new Memoizer<>(k -> "v");
        assertThrows(NullPointerException.class, () -> memo.get(null));
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void theRaceHarnessDoesCatchAMemoizerThatChecksThenActs() {
        // Without this, the test below could pass because the harness is too gentle to ever expose a race.
        assertTimeoutPreemptively(LIMIT, () -> {
            AtomicInteger entered = new AtomicInteger();
            AtomicInteger computations = new AtomicInteger();
            NaiveMemoizer<String, String> naive = new NaiveMemoizer<>(k -> {
                computations.incrementAndGet();
                pause(entered, 10_000); // released the moment a second thread also starts computing
                return "v";
            });
            race(8, naive::get);
            assertTrue(computations.get() >= 2,
                "the naive memoizer computed " + computations.get() + " time(s); the harness failed to expose its race");
        });
    }

    @Test
    void manyThreadsAskingForTheSameKeyComputeItExactlyOnce() {
        assertTimeoutPreemptively(LIMIT, () -> {
            AtomicInteger entered = new AtomicInteger();
            AtomicInteger computations = new AtomicInteger();
            Memoizer<String, Object> memo = new Memoizer<>(k -> {
                computations.incrementAndGet();
                pause(entered, 150); // hold the computation open while the others pile up behind it
                return new Object();
            });
            List<Object> results = race(32, memo::get);

            assertEquals(1, computations.get(), "the work was repeated");
            assertEquals(1, entered.get(), "a second thread got into the computation");
            Object first = results.get(0);
            for (Object result : results) {
                assertSame(first, result, "every caller must receive the one computed value");
            }
        });
    }

    @Test
    void differentKeysComputeInParallelRatherThanQueueingBehindEachOther() {
        // Two keys, each computation waits for the other to have started. If the memoizer serialised its work
        // behind one lock, the first computation would wait forever for a second that can never start.
        assertTimeoutPreemptively(LIMIT, () -> {
            CountDownLatch bothStarted = new CountDownLatch(2);
            Memoizer<String, String> memo = new Memoizer<>(k -> {
                bothStarted.countDown();
                try {
                    if (!bothStarted.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the other key never started: computations are serialised");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return k.toUpperCase();
            });
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<String> a = executor.submit(() -> memo.get("a"));
                Future<String> b = executor.submit(() -> memo.get("b"));
                assertEquals("A", a.get(30, TimeUnit.SECONDS));
                assertEquals("B", b.get(30, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        });
    }

    @Test
    void everyThreadWaitingOnAFailingComputationSeesTheFailureAndTheNextCallStartsAfresh() {
        assertTimeoutPreemptively(LIMIT, () -> {
            AtomicInteger entered = new AtomicInteger();
            AtomicInteger computations = new AtomicInteger();
            Memoizer<String, String> memo = new Memoizer<>(k -> {
                int attempt = computations.incrementAndGet();
                pause(entered, 150);
                if (attempt == 1) {
                    throw new IllegalStateException("boom");
                }
                return "recovered";
            });
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<String>> outcomes = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    outcomes.add(executor.submit(() -> {
                        go.await();
                        try {
                            return memo.get("k");
                        } catch (IllegalStateException e) {
                            return "failed: " + e.getMessage();
                        }
                    }));
                }
                go.countDown();
                int failed = 0;
                for (Future<String> outcome : outcomes) {
                    if (outcome.get(30, TimeUnit.SECONDS).equals("failed: boom")) {
                        failed++;
                    }
                }
                assertTrue(failed >= 1, "at least the thread that ran the computation sees its failure");
                assertEquals("recovered", memo.get("k"), "a later call recomputes instead of replaying the failure");
            } finally {
                executor.shutdownNow();
            }
        });
    }
}
