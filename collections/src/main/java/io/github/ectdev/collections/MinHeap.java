package io.github.ectdev.collections;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * A binary min-heap: {@code java.util.PriorityQueue} without the {@code Collection} baggage.
 *
 * <p>The heap is a complete binary tree stored in an array, with the children of slot {@code i} at {@code 2i + 1}
 * and {@code 2i + 2}. The only invariant is local: <em>no element is smaller than its parent</em>. That is weaker
 * than sorted order, and cheap to keep: {@link #add} puts the new element at the end and swaps it up while it is
 * smaller than its parent, {@link #poll} moves the last element to the root and swaps it down toward the smaller
 * child. Each walks one root-to-leaf path, so both are O(log n); {@link #peek} is O(1).
 *
 * <p>Building a heap from a collection with {@link #MinHeap(Comparator, Collection)} does not call {@link #add} n
 * times (O(n log n)). It copies the elements in and sifts down from the last parent to the root, which is O(n):
 * most nodes are near the bottom, where sifting down costs almost nothing.
 *
 * <p>Equal elements come out in an unspecified order; the heap is not stable. Not thread-safe. Elements must not be
 * {@code null}.
 *
 * @param <E> element type
 */
public final class MinHeap<E> {

    private static final int DEFAULT_CAPACITY = 11;

    private final Comparator<? super E> order;
    private Object[] slots;
    private int size;

    public MinHeap(Comparator<? super E> order) {
        this.order = Objects.requireNonNull(order, "order");
        this.slots = new Object[DEFAULT_CAPACITY];
    }

    /** Builds a heap holding {@code initial}, in O(n). */
    public MinHeap(Comparator<? super E> order, Collection<? extends E> initial) {
        this.order = Objects.requireNonNull(order, "order");
        this.slots = new Object[Math.max(DEFAULT_CAPACITY, initial.size())];
        for (E element : initial) {
            slots[size++] = Objects.requireNonNull(element, "element");
        }
        for (int parent = (size >>> 1) - 1; parent >= 0; parent--) {
            siftDown(parent);
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Inserts {@code element}. O(log n). */
    public void add(E element) {
        Objects.requireNonNull(element, "element");
        if (size == slots.length) {
            slots = Arrays.copyOf(slots, DynamicArray.newCapacity(slots.length, size + 1));
        }
        slots[size] = element;
        siftUp(size);
        size++;
    }

    /** The smallest element, without removing it. O(1). @throws NoSuchElementException if empty */
    public E peek() {
        if (size == 0) {
            throw new NoSuchElementException("heap is empty");
        }
        return at(0);
    }

    /** Removes and returns the smallest element. O(log n). @throws NoSuchElementException if empty */
    public E poll() {
        E smallest = peek();
        size--;
        if (size > 0) {
            slots[0] = slots[size];
            siftDown(0);
        }
        slots[size] = null;
        return smallest;
    }

    public void clear() {
        Arrays.fill(slots, 0, size, null);
        size = 0;
    }

    // ------------------------------------------------------------------ sifting

    private void siftUp(int child) {
        Object moving = slots[child];
        while (child > 0) {
            int parent = (child - 1) >>> 1;
            if (order.compare(cast(moving), at(parent)) >= 0) {
                break;
            }
            slots[child] = slots[parent];
            child = parent;
        }
        slots[child] = moving;
    }

    private void siftDown(int parent) {
        Object moving = slots[parent];
        int half = size >>> 1; // slots at or beyond this index are leaves
        while (parent < half) {
            int child = 2 * parent + 1;
            int right = child + 1;
            if (right < size && order.compare(at(right), at(child)) < 0) {
                child = right;
            }
            if (order.compare(cast(moving), at(child)) <= 0) {
                break;
            }
            slots[parent] = slots[child];
            parent = child;
        }
        slots[parent] = moving;
    }

    @SuppressWarnings("unchecked") // only add and the collection constructor store into slots, and they store E
    private E at(int slot) {
        return (E) slots[slot];
    }

    @SuppressWarnings("unchecked")
    private E cast(Object element) {
        return (E) element;
    }

    // ------------------------------------------------------------------ test hook

    /** Whether no element is smaller than its parent; the one property every operation must preserve. */
    boolean isHeap() {
        for (int child = 1; child < size; child++) {
            if (order.compare(at(child), at((child - 1) >>> 1)) < 0) {
                return false;
            }
        }
        return true;
    }
}
