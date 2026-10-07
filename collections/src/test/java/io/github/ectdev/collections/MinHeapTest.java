package io.github.ectdev.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MinHeapTest {

    private static <E> List<E> drain(MinHeap<E> heap) {
        List<E> out = new ArrayList<>();
        while (!heap.isEmpty()) {
            out.add(heap.poll());
        }
        return out;
    }

    @Test
    void pollsInAscendingOrder() {
        MinHeap<Integer> heap = new MinHeap<>(Comparator.naturalOrder());
        for (int value : new int[] {5, 3, 8, 1, 9, 2, 7}) {
            heap.add(value);
        }
        assertEquals(7, heap.size());
        assertEquals(1, heap.peek());
        assertEquals(List.of(1, 2, 3, 5, 7, 8, 9), drain(heap));
        assertTrue(heap.isEmpty());
    }

    @Test
    void duplicatesAreAllReturned() {
        MinHeap<Integer> heap = new MinHeap<>(Comparator.naturalOrder());
        for (int value : new int[] {4, 2, 4, 2, 4, 1, 1}) {
            heap.add(value);
        }
        assertEquals(List.of(1, 1, 2, 2, 4, 4, 4), drain(heap));
    }

    @Test
    void aReversedComparatorGivesAMaxHeap() {
        MinHeap<Integer> heap = new MinHeap<>(Comparator.reverseOrder());
        for (int value : new int[] {5, 3, 8, 1}) {
            heap.add(value);
        }
        assertEquals(List.of(8, 5, 3, 1), drain(heap));
    }

    @Test
    void anyComparatorWorksNotJustNaturalOrder() {
        MinHeap<String> heap = new MinHeap<>(Comparator.comparingInt(String::length));
        for (String word : List.of("ccc", "a", "dddd", "bb")) {
            heap.add(word);
        }
        assertEquals(List.of("a", "bb", "ccc", "dddd"), drain(heap));
    }

    @Test
    void peekDoesNotRemove() {
        MinHeap<Integer> heap = new MinHeap<>(Comparator.naturalOrder());
        heap.add(2);
        heap.add(1);
        assertEquals(1, heap.peek());
        assertEquals(1, heap.peek());
        assertEquals(2, heap.size());
    }

    @Test
    void emptyHeapRefusesPeekAndPoll() {
        MinHeap<Integer> heap = new MinHeap<>(Comparator.naturalOrder());
        assertThrows(NoSuchElementException.class, heap::peek);
        assertThrows(NoSuchElementException.class, heap::poll);
        heap.add(1);
        heap.poll();
        assertThrows(NoSuchElementException.class, heap::poll);
    }

    @Test
    void nullsAreRejected() {
        MinHeap<Integer> heap = new MinHeap<>(Comparator.naturalOrder());
        assertThrows(NullPointerException.class, () -> heap.add(null));
        assertThrows(NullPointerException.class, () -> new MinHeap<Integer>(null));
        List<Integer> withNull = new ArrayList<>(List.of(1, 2));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new MinHeap<>(Comparator.<Integer>naturalOrder(), withNull));
    }

    @Test
    void clearEmptiesTheHeapAndItStaysUsable() {
        MinHeap<Integer> heap = new MinHeap<>(Comparator.naturalOrder());
        heap.add(3);
        heap.add(1);
        heap.clear();
        assertTrue(heap.isEmpty());
        heap.add(9);
        assertEquals(9, heap.peek());
    }

    @Test
    void growsPastItsInitialCapacity() {
        MinHeap<Integer> heap = new MinHeap<>(Comparator.naturalOrder());
        for (int i = 1000; i > 0; i--) {
            heap.add(i);
        }
        assertTrue(heap.isHeap());
        assertEquals(1, heap.poll());
        assertEquals(2, heap.poll());
        assertEquals(998, heap.size());
    }

    // ------------------------------------------------------------------ building from a collection

    @Test
    void heapifyingACollectionProducesAValidHeap() {
        List<Integer> input = List.of(9, 8, 7, 6, 5, 4, 3, 2, 1, 0);
        MinHeap<Integer> heap = new MinHeap<>(Comparator.naturalOrder(), input);
        assertTrue(heap.isHeap());
        assertEquals(10, heap.size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), drain(heap));
    }

    @Test
    void heapifyingTheEmptyAndSingleCases() {
        assertTrue(new MinHeap<Integer>(Comparator.naturalOrder(), List.of()).isEmpty());
        MinHeap<Integer> one = new MinHeap<>(Comparator.naturalOrder(), List.of(42));
        assertEquals(42, one.peek());
        assertEquals(1, one.size());
    }

    @Test
    void theInputCollectionIsNotModified() {
        List<Integer> input = new ArrayList<>(List.of(3, 1, 2));
        new MinHeap<>(Comparator.<Integer>naturalOrder(), input);
        assertEquals(List.of(3, 1, 2), input);
    }

    // ------------------------------------------------------------------ against PriorityQueue and sorting

    /**
     * Two oracles. {@link PriorityQueue} must agree on every polled value as operations interleave; and the heap's
     * invariant (no element smaller than its parent) must hold after <em>every</em> operation, not only at the end,
     * because a heap can return correct answers for a long time while being subtly broken.
     */
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 2026, 8675309})
    void behavesLikePriorityQueueUnderRandomOperations(long seed) {
        Random random = new Random(seed);
        MinHeap<Integer> actual = new MinHeap<>(Comparator.naturalOrder());
        PriorityQueue<Integer> oracle = new PriorityQueue<>();

        for (int step = 0; step < 5000; step++) {
            if (oracle.isEmpty() || random.nextInt(100) < 60) {
                int value = random.nextInt(100); // few distinct values: plenty of duplicates
                actual.add(value);
                oracle.add(value);
            } else {
                assertEquals(oracle.peek(), actual.peek(), "peek at step " + step);
                assertEquals(oracle.poll(), actual.poll(), "poll at step " + step);
            }
            assertEquals(oracle.size(), actual.size(), "size at step " + step);
            assertTrue(actual.isHeap(), "heap invariant broken at step " + step);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 7, 42, 99, 2026})
    void drainingAHeapifiedCollectionEqualsSortingIt(long seed) {
        Random random = new Random(seed);
        for (int size : new int[] {0, 1, 2, 3, 10, 100, 1000}) {
            List<Integer> input = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                input.add(random.nextInt(50) - 25); // negatives and duplicates
            }
            MinHeap<Integer> heap = new MinHeap<>(Comparator.naturalOrder(), input);
            assertTrue(heap.isHeap(), "after heapify, size " + size);

            List<Integer> sorted = new ArrayList<>(input);
            Collections.sort(sorted);
            assertEquals(sorted, drain(heap), "size " + size);
        }
    }

    @Test
    void isHeapActuallyDetectsABrokenHeap() {
        // Guards against a vacuous invariant check, the way a smoke detector gets tested with smoke: build a valid
        // heap, then change the order its elements were arranged for. Parent 1 over child 2 is fine ascending and
        // wrong descending, so a working isHeap() must flip from true to false.
        AtomicBoolean descending = new AtomicBoolean(false);
        Comparator<Integer> flippable = (a, b) -> descending.get() ? b.compareTo(a) : a.compareTo(b);
        MinHeap<Integer> heap = new MinHeap<>(flippable, List.of(1, 2, 3));
        assertTrue(heap.isHeap());
        descending.set(true);
        assertFalse(heap.isHeap());
    }
}
