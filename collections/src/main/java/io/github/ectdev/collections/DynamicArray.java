package io.github.ectdev.collections;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * A resizable array: {@code java.util.ArrayList} rebuilt to see where its costs come from.
 *
 * <p>Elements live in one {@code Object[]} that is replaced by a larger copy when it fills.
 * The array grows by about half (new capacity = old + old/2 + 1), so the copying cost of
 * {@code n} appends is a geometric series bounded by a constant times {@code n}: appending is
 * amortised O(1), while inserting or removing in the middle is O(n) because the tail has to
 * shift.
 *
 * <p>Two details are easy to get wrong and are pinned by tests:
 * <ul>
 *   <li>a removed element's slot is set to {@code null}; otherwise the array keeps the object
 *       alive after the list has forgotten it;</li>
 *   <li>iterators are fail-fast: any structural change made behind an iterator's back is
 *       reported with a {@link ConcurrentModificationException} instead of silently skipping or
 *       repeating elements.</li>
 * </ul>
 *
 * <p>Not thread-safe. Elements may be {@code null}.
 *
 * @param <E> element type
 */
public final class DynamicArray<E> implements Iterable<E> {

    static final int DEFAULT_CAPACITY = 10;

    /** The largest array this class will ask for; the JDK keeps the same margin for VM header words. */
    static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    private Object[] elements;
    private int size;
    /** Incremented on every structural change; iterators compare it with their own copy. */
    private int modCount;

    public DynamicArray() {
        this(DEFAULT_CAPACITY);
    }

    public DynamicArray(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + initialCapacity);
        }
        this.elements = new Object[initialCapacity];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Appends {@code element}. Amortised O(1). */
    public void add(E element) {
        ensureCapacity(size + 1);
        elements[size++] = element;
        modCount++;
    }

    /** Inserts {@code element} before the current element at {@code index} ({@code index == size} appends). O(n). */
    public void add(int index, E element) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for insertion into length " + size);
        }
        ensureCapacity(size + 1);
        System.arraycopy(elements, index, elements, index + 1, size - index);
        elements[index] = element;
        size++;
        modCount++;
    }

    @SuppressWarnings("unchecked") // only add/add(int, E)/set ever store into the array, and they store E
    public E get(int index) {
        Objects.checkIndex(index, size);
        return (E) elements[index];
    }

    /** Replaces the element at {@code index} and returns the old one. Not a structural change. */
    public E set(int index, E element) {
        E old = get(index);
        elements[index] = element;
        return old;
    }

    /** Removes and returns the element at {@code index}, shifting the tail left. O(n). */
    public E remove(int index) {
        E old = get(index);
        int moved = size - index - 1;
        if (moved > 0) {
            System.arraycopy(elements, index + 1, elements, index, moved);
        }
        elements[--size] = null; // do not keep the removed object reachable
        modCount++;
        return old;
    }

    /** Index of the first element equal to {@code target} (which may be {@code null}), or -1. */
    public int indexOf(Object target) {
        for (int i = 0; i < size; i++) {
            if (Objects.equals(target, elements[i])) {
                return i;
            }
        }
        return -1;
    }

    public boolean contains(Object target) {
        return indexOf(target) >= 0;
    }

    public void clear() {
        Arrays.fill(elements, 0, size, null);
        size = 0;
        modCount++;
    }

    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DynamicArray<?> that) || that.size != size) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (!Objects.equals(elements[i], that.elements[i])) {
                return false;
            }
        }
        return true;
    }

    /** The same formula as {@link java.util.List#hashCode()}, so equal contents hash equally across the two types. */
    @Override
    public int hashCode() {
        int hash = 1;
        for (int i = 0; i < size; i++) {
            hash = 31 * hash + Objects.hashCode(elements[i]);
        }
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(elements[i] == this ? "(this array)" : String.valueOf(elements[i]));
        }
        return out.append(']').toString();
    }

    // ------------------------------------------------------------------ growth

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > elements.length) {
            elements = Arrays.copyOf(elements, newCapacity(elements.length, minCapacity));
        }
    }

    /**
     * The capacity to grow to: one and a half times the old one (plus one, so that 0 and 1 grow too), or
     * {@code minCapacity} if that is larger, but never past {@link #MAX_ARRAY_SIZE}.
     *
     * <p>The arithmetic is done in {@code long} so that near {@code Integer.MAX_VALUE} an overflow cannot wrap into a
     * negative size that would then be accepted.
     */
    static int newCapacity(int oldCapacity, int minCapacity) {
        if (minCapacity < 0) { // the caller's size + 1 overflowed
            throw new OutOfMemoryError("Required array size too large");
        }
        long grown = (long) oldCapacity + (oldCapacity >> 1) + 1;
        long target = Math.max(grown, minCapacity);
        if (target <= MAX_ARRAY_SIZE) {
            return (int) target;
        }
        if (minCapacity > MAX_ARRAY_SIZE) {
            throw new OutOfMemoryError("Required array size too large");
        }
        return MAX_ARRAY_SIZE;
    }

    // ------------------------------------------------------------------ test hooks

    /** The length of the backing array. */
    int capacity() {
        return elements.length;
    }

    /** What the backing array holds at {@code slot}, including slots past {@code size} (which must be null). */
    Object rawSlot(int slot) {
        return elements[slot];
    }

    // ------------------------------------------------------------------ iterator

    private final class Itr implements Iterator<E> {
        private int cursor;
        private int lastReturned = -1;
        private int expectedModCount = modCount;

        @Override
        public boolean hasNext() {
            return cursor != size;
        }

        @Override
        public E next() {
            checkForComodification();
            if (cursor >= size) {
                throw new NoSuchElementException();
            }
            lastReturned = cursor;
            return get(cursor++);
        }

        @Override
        public void remove() {
            if (lastReturned < 0) {
                throw new IllegalStateException("next() has not been called, or remove() was already called");
            }
            checkForComodification();
            DynamicArray.this.remove(lastReturned);
            cursor = lastReturned;
            lastReturned = -1;
            expectedModCount = modCount;
        }

        private void checkForComodification() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
        }
    }
}
