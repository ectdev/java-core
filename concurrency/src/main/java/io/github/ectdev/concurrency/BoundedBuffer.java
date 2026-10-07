package io.github.ectdev.concurrency;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A fixed-capacity FIFO queue whose producers wait when it is full and whose consumers wait when it is empty:
 * the classic producer-consumer buffer, the core of {@code java.util.concurrent.ArrayBlockingQueue}.
 *
 * <p>One lock guards all the state. Two {@link Condition}s hang off it, one per question a thread might be
 * waiting on: "is there room?" ({@code notFull}) and "is there something?" ({@code notEmpty}). Using two
 * conditions instead of one means a {@code put} only ever wakes a consumer and a {@code take} only ever wakes a
 * producer, and each can wake exactly one thread ({@code signal}) because each event satisfies exactly one waiter.
 *
 * <p>Three rules make code like this correct, and each is visible below:
 * <ol>
 *   <li><b>Wait in a loop that re-tests the condition.</b> A thread can be woken without its condition being
 *       true (spurious wakeups are allowed, and another thread may have taken the slot first), so {@code await}
 *       is always inside {@code while (full)}, never {@code if}.</li>
 *   <li><b>Test the condition before the clock in timed waits.</b> A thread woken at the very moment its time
 *       runs out must still take an item that is there. The loop checks the state first and gives up only when
 *       the state says there is nothing to take <em>and</em> the time is gone.</li>
 *   <li><b>Time out against the remaining time.</b> {@code awaitNanos} returns what is left, which is passed to
 *       the next wait, so repeated wakeups cannot stretch a one-second timeout into several.</li>
 * </ol>
 *
 * <p>The elements live in a circular array: {@code head} is where the next element will be taken, {@code tail}
 * where the next will be put, and both wrap around with a compare instead of a modulo. Elements must not be
 * {@code null}, because {@link #poll} uses {@code null} to mean "timed out".
 *
 * @param <E> element type
 */
public final class BoundedBuffer<E> {

    private final Object[] items;
    private int head;
    private int tail;
    private int count;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public BoundedBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        this.items = new Object[capacity];
    }

    public int capacity() {
        return items.length;
    }

    /** The number of elements right now. By the time the caller looks at it, it may already have changed. */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** Appends {@code element}, waiting for room if the buffer is full. */
    public void put(E element) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                notFull.await();
            }
            enqueue(element);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Appends {@code element}, waiting up to {@code timeout} for room.
     *
     * @return {@code true} if the element was added, {@code false} if the time ran out first
     */
    public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        long remaining = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (remaining <= 0L) {
                    return false;
                }
                remaining = notFull.awaitNanos(remaining);
            }
            enqueue(element);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Removes and returns the oldest element, waiting for one if the buffer is empty. */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes and returns the oldest element, waiting up to {@code timeout} for one.
     *
     * @return the element, or {@code null} if the time ran out first
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (remaining <= 0L) {
                    return null;
                }
                remaining = notEmpty.awaitNanos(remaining);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /** What the backing array holds at {@code slot}; a taken element's slot must be null. A test hook. */
    Object rawSlot(int slot) {
        lock.lock();
        try {
            return items[slot];
        } finally {
            lock.unlock();
        }
    }

    // Both helpers are called with the lock held.

    private void enqueue(E element) {
        items[tail] = element;
        if (++tail == items.length) {
            tail = 0;
        }
        count++;
        notEmpty.signal(); // one new element can satisfy one waiting consumer
    }

    @SuppressWarnings("unchecked") // only enqueue stores into items, and it stores E
    private E dequeue() {
        E element = (E) items[head];
        items[head] = null; // do not keep a taken element reachable from the buffer
        if (++head == items.length) {
            head = 0;
        }
        count--;
        notFull.signal(); // one freed slot can satisfy one waiting producer
        return element;
    }
}
