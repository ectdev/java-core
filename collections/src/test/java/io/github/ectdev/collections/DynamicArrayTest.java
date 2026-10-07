package io.github.ectdev.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DynamicArrayTest {

    @SafeVarargs // the varargs array is only read, never exposed
    private static <T> DynamicArray<T> arrayOf(T... items) {
        DynamicArray<T> array = new DynamicArray<>();
        for (T item : items) {
            array.add(item);
        }
        return array;
    }

    private static <T> List<T> contents(DynamicArray<T> array) {
        List<T> out = new ArrayList<>();
        for (T item : array) {
            out.add(item);
        }
        return out;
    }

    // ------------------------------------------------------------------ basics

    @Test
    void appendsAndReadsBackInOrder() {
        DynamicArray<String> array = arrayOf("a", "b", "c");
        assertEquals(3, array.size());
        assertEquals("a", array.get(0));
        assertEquals("c", array.get(2));
        assertEquals(List.of("a", "b", "c"), contents(array));
    }

    @Test
    void insertsAtTheFrontMiddleAndEnd() {
        DynamicArray<Integer> array = arrayOf(2, 4);
        array.add(0, 1);          // front
        array.add(2, 3);          // middle
        array.add(array.size(), 5); // end: index == size appends
        assertEquals(List.of(1, 2, 3, 4, 5), contents(array));
    }

    @Test
    void removeReturnsTheElementAndClosesTheGap() {
        DynamicArray<String> array = arrayOf("a", "b", "c", "d");
        assertEquals("b", array.remove(1));
        assertEquals("d", array.remove(2));
        assertEquals("a", array.remove(0));
        assertEquals(List.of("c"), contents(array));
    }

    @Test
    void setReplacesAndReturnsTheOldElement() {
        DynamicArray<String> array = arrayOf("a", "b");
        assertEquals("b", array.set(1, "z"));
        assertEquals(List.of("a", "z"), contents(array));
    }

    @Test
    void nullElementsAreStoredAndFound() {
        DynamicArray<String> array = arrayOf("a", null, "b", null);
        assertEquals(1, array.indexOf(null));
        assertTrue(array.contains(null));
        assertEquals(2, array.indexOf("b"));
        assertEquals(-1, array.indexOf("missing"));
        assertFalse(arrayOf("a").contains(null));
    }

    @Test
    void clearEmptiesTheArray() {
        DynamicArray<String> array = arrayOf("a", "b");
        array.clear();
        assertTrue(array.isEmpty());
        assertEquals(0, array.size());
        assertThrows(IndexOutOfBoundsException.class, () -> array.get(0));
    }

    // ------------------------------------------------------------------ index checks

    @ParameterizedTest
    @ValueSource(ints = {-1, 3, 4, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void getAndSetAndRemoveRejectIndexesOutsideZeroToSizeMinusOne(int index) {
        DynamicArray<String> array = arrayOf("a", "b", "c");
        assertThrows(IndexOutOfBoundsException.class, () -> array.get(index));
        assertThrows(IndexOutOfBoundsException.class, () -> array.set(index, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> array.remove(index));
        assertEquals(List.of("a", "b", "c"), contents(array), "a rejected call must not change anything");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 4, 100})
    void insertRejectsIndexesPastSizeButAcceptsSizeItself(int index) {
        DynamicArray<String> array = arrayOf("a", "b", "c");
        assertThrows(IndexOutOfBoundsException.class, () -> array.add(index, "x"));
        array.add(3, "end"); // index == size is the append position
        assertEquals("end", array.get(3));
    }

    // ------------------------------------------------------------------ growth, by hand

    @ParameterizedTest(name = "newCapacity({0}, {1}) = {2}")
    @CsvSource({
        "0,  1,  1",    // from empty: 0 + 0 + 1
        "1,  2,  2",    // 1 + 0 + 1
        "2,  3,  4",    // 2 + 1 + 1
        "10, 11, 16",   // the default array, full: 10 + 5 + 1
        "16, 17, 25",   // 16 + 8 + 1
        "10, 40, 40",   // a bulk request larger than the usual growth wins
    })
    void growsByAboutHalfPlusOne(int oldCapacity, int minCapacity, int expected) {
        assertEquals(expected, DynamicArray.newCapacity(oldCapacity, minCapacity));
    }

    @Test
    void growthIsClampedBelowTheLimitAndRefusesBeyondIt() {
        int limit = DynamicArray.MAX_ARRAY_SIZE;
        assertEquals(limit, DynamicArray.newCapacity(limit - 5, limit - 4), "clamped, not wrapped to negative");
        assertEquals(limit, DynamicArray.newCapacity(limit, limit));
        assertThrows(OutOfMemoryError.class, () -> DynamicArray.newCapacity(limit, limit + 1));
        assertThrows(OutOfMemoryError.class, () -> DynamicArray.newCapacity(limit, Integer.MIN_VALUE),
            "an int overflow in the caller arrives as a negative minimum and must not be accepted");
    }

    @Test
    void theEleventhElementOfADefaultArrayTriggersTheOnlyGrowth() {
        DynamicArray<Integer> array = new DynamicArray<>();
        assertEquals(10, array.capacity());
        for (int i = 0; i < 10; i++) {
            array.add(i);
        }
        assertEquals(10, array.capacity(), "full is not yet grown");
        array.add(10);
        assertEquals(16, array.capacity());
        assertEquals(11, array.size());
    }

    @Test
    void anEmptyCapacityArrayStillGrows() {
        DynamicArray<Integer> array = new DynamicArray<>(0);
        for (int i = 0; i < 100; i++) {
            array.add(i);
        }
        assertEquals(100, array.size());
        assertEquals(99, array.get(99));
    }

    @Test
    void negativeCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DynamicArray<String>(-1));
    }

    // ------------------------------------------------------------------ no loitering

    @Test
    @DisplayName("a removed element's slot is nulled, so the array does not keep it alive")
    void removedAndClearedSlotsAreNulled() {
        DynamicArray<Object> array = arrayOf(new Object(), new Object(), new Object());
        array.remove(0);
        assertNull(array.rawSlot(2), "the vacated tail slot");
        array.clear();
        assertNull(array.rawSlot(0));
        assertNull(array.rawSlot(1));
    }

    // ------------------------------------------------------------------ iteration

    @Test
    void iteratorWalksEveryElementThenStops() {
        Iterator<String> it = arrayOf("a", "b").iterator();
        assertEquals("a", it.next());
        assertEquals("b", it.next());
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    /** Every way of changing the shape of the array, each of which must trip an iterator that did not make it. */
    private static Map<String, Consumer<DynamicArray<String>>> structuralChanges() {
        Map<String, Consumer<DynamicArray<String>>> changes = new LinkedHashMap<>();
        changes.put("add(element)", array -> array.add("new"));
        changes.put("add(index, element)", array -> array.add(0, "new"));
        changes.put("remove(index)", array -> array.remove(0));
        changes.put("clear()", DynamicArray::clear);
        return changes;
    }

    @Test
    void everyStructuralChangeBehindAnIteratorsBackIsReported() {
        structuralChanges().forEach((name, change) -> {
            DynamicArray<String> array = arrayOf("a", "b", "c");
            Iterator<String> it = array.iterator();
            it.next();
            change.accept(array);
            assertThrows(ConcurrentModificationException.class, it::next, name + " went unnoticed by the iterator");
        });
    }

    @Test
    void replacingAnElementIsNotAStructuralChange() {
        DynamicArray<String> array = arrayOf("a", "b");
        Iterator<String> it = array.iterator();
        it.next();
        array.set(1, "z");
        assertEquals("z", it.next());
    }

    @Test
    void iteratorRemoveDeletesTheLastReturnedElementAndIterationContinues() {
        DynamicArray<Integer> array = arrayOf(1, 2, 3, 4, 5, 6);
        for (Iterator<Integer> it = array.iterator(); it.hasNext(); ) {
            if (it.next() % 2 == 0) {
                it.remove();
            }
        }
        assertEquals(List.of(1, 3, 5), contents(array));
    }

    @Test
    void iteratorRemoveNeedsANextFirstAndCannotRepeat() {
        DynamicArray<Integer> array = arrayOf(1, 2);
        Iterator<Integer> it = array.iterator();
        assertThrows(IllegalStateException.class, it::remove);
        it.next();
        it.remove();
        assertThrows(IllegalStateException.class, it::remove);
    }

    // ------------------------------------------------------------------ equality

    @Test
    void equalsAndHashCodeFollowTheContents() {
        DynamicArray<String> a = arrayOf("x", null, "y");
        DynamicArray<String> b = arrayOf("x", null, "y");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.set(2, "z");
        assertFalse(a.equals(b));
        assertFalse(a.equals(null));
        assertFalse(a.equals("not an array"));
    }

    @Test
    void hashCodeMatchesTheListContract() {
        assertEquals(Arrays.asList("x", null, "y").hashCode(), arrayOf("x", null, "y").hashCode());
        assertEquals(List.of().hashCode(), new DynamicArray<String>().hashCode());
    }

    @Test
    void toStringListsTheElements() {
        assertEquals("[a, null, c]", arrayOf("a", null, "c").toString());
        assertEquals("[]", new DynamicArray<String>().toString());
    }

    // ------------------------------------------------------------------ against java.util.ArrayList

    /**
     * Applies the same random operations to this class and to {@link ArrayList} and requires the same outcome at
     * every step: the same returned value, or an index-out-of-bounds from both. The indexes deliberately range
     * a little outside the valid span, so the error paths are compared as well. Seeds are fixed, so a failure
     * reproduces.
     */
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 7, 11, 42, 99, 2026, 31337, 8675309})
    void behavesLikeArrayListUnderRandomOperations(long seed) {
        Random random = new Random(seed);
        DynamicArray<Integer> actual = new DynamicArray<>(random.nextInt(4)); // small starts exercise growth
        List<Integer> oracle = new ArrayList<>();

        for (int step = 0; step < 3000; step++) {
            int index = random.nextInt(oracle.size() + 5) - 2; // from -2 to size + 2
            int value = random.nextInt(50);
            Integer element = random.nextInt(10) == 0 ? null : value;
            int operation = random.nextInt(100);

            Object expected;
            Object observed;
            if (operation < 35) {
                expected = attempt(() -> oracle.add(element));
                observed = attempt(() -> { actual.add(element); return true; });
            } else if (operation < 55) {
                expected = attempt(() -> { oracle.add(index, element); return null; });
                observed = attempt(() -> { actual.add(index, element); return null; });
            } else if (operation < 75) {
                expected = attempt(() -> oracle.remove(index));
                observed = attempt(() -> actual.remove(index));
            } else if (operation < 85) {
                expected = attempt(() -> oracle.set(index, element));
                observed = attempt(() -> actual.set(index, element));
            } else if (operation < 95) {
                expected = attempt(() -> oracle.get(index));
                observed = attempt(() -> actual.get(index));
            } else if (operation < 99) {
                expected = oracle.indexOf(element);
                observed = actual.indexOf(element);
            } else {
                oracle.clear();
                actual.clear();
                expected = observed = "cleared";
            }

            assertEquals(expected, observed, "step " + step + ", operation " + operation + ", index " + index);
            assertEquals(oracle.size(), actual.size(), "size after step " + step);
        }
        assertEquals(oracle, contents(actual));
        assertEquals(oracle.hashCode(), actual.hashCode());
    }

    /** The result of {@code call}, or a marker for any index-out-of-bounds, so two implementations compare equal. */
    private static Object attempt(Supplier<Object> call) {
        try {
            return call.get();
        } catch (IndexOutOfBoundsException e) {
            return "IndexOutOfBounds";
        }
    }
}
