package io.github.ectdev.concurrency;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Function;

/**
 * A cache that computes each value at most once, even when many threads ask for the same key at the same time,
 * and makes the other threads wait for the first one's answer instead of repeating the work.
 *
 * <p>This is the pattern from <em>Java Concurrency in Practice</em> (chapter 5), and it is worth seeing how it
 * gets there, because each natural first attempt is wrong in a different way:
 * <ul>
 *   <li><b>A synchronized method around a plain map</b> is correct but serialises everything: a slow
 *       computation for one key blocks lookups of every other key.</li>
 *   <li><b>A concurrent map with check-then-put</b> ({@code if (!map.containsKey(k)) map.put(k, compute(k))}) is
 *       fast but wrong: two threads can both find the key missing and both compute it. The check and the put
 *       are individually atomic, and together they are not.</li>
 *   <li><b>A concurrent map of futures, with {@code putIfAbsent}</b> fixes both. The map stores a
 *       {@link FutureTask} <em>before</em> the work starts. {@code putIfAbsent} is atomic, so exactly one thread
 *       wins the right to run the task; every other thread finds the same future and waits on it. Work for
 *       different keys proceeds in parallel.</li>
 * </ul>
 *
 * <p><b>A failed computation is not remembered.</b> If {@code compute} throws, the entry is removed, and the next
 * caller tries again; otherwise one transient failure would be cached forever. Threads that were already waiting
 * on the failed computation all see that failure. The removal is conditional ({@code remove(key, task)}), so it
 * can only ever remove the failed task, never a fresh one that a faster thread has since put there.
 *
 * <p>{@code compute} must not return {@code null} (that is treated as a failure, and not cached) and must not
 * call back into the same memoizer for the same key, which would wait on itself.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class Memoizer<K, V> {

    private final ConcurrentMap<K, FutureTask<V>> cache = new ConcurrentHashMap<>();
    private final Function<? super K, ? extends V> compute;

    public Memoizer(Function<? super K, ? extends V> compute) {
        this.compute = Objects.requireNonNull(compute, "compute");
    }

    /**
     * The value for {@code key}, computing it in this thread if no thread has started to, or waiting for the thread
     * that has.
     *
     * @throws InterruptedException if interrupted while waiting for another thread's computation
     * @throws RuntimeException     whatever {@code compute} threw
     */
    public V get(K key) throws InterruptedException {
        Objects.requireNonNull(key, "key");
        FutureTask<V> task = cache.get(key);
        if (task == null) {
            FutureTask<V> mine = new FutureTask<>(() -> Objects.requireNonNull(compute.apply(key), "compute returned null"));
            task = cache.putIfAbsent(key, mine);
            if (task == null) { // we won the race to publish: the work is ours to run
                task = mine;
                mine.run();
            }
        }
        try {
            return task.get();
        } catch (ExecutionException failure) {
            cache.remove(key, task); // forget the failure; only this task, never a newer one
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause); // unreachable for a Function, kept so the method is total
        }
    }

    /** The number of keys that have a finished or running computation (a failed one is not counted). */
    public int size() {
        return cache.size();
    }
}
