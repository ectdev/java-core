package io.github.ectdev.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChainedHashMapTest {

    private static <K, V> Map<K, V> snapshot(ChainedHashMap<K, V> map) {
        Map<K, V> out = new HashMap<>();
        for (ChainedHashMap.Entry<K, V> entry : map) {
            assertFalse(out.containsKey(entry.key()), "the iterator returned " + entry.key() + " twice");
            out.put(entry.key(), entry.value());
        }
        return out;
    }

    /** A key whose every instance lands in the same bucket, yet which is still correctly comparable. */
    private record Colliding(int id) {
        @Override
        public int hashCode() {
            return 7;
        }
    }

    // ------------------------------------------------------------------ basics

    @Test
    void putGetRemoveRoundTrip() {
        ChainedHashMap<String, Integer> map = new ChainedHashMap<>();
        assertNull(map.put("one", 1));
        assertNull(map.put("two", 2));
        assertEquals(1, map.get("one"));
        assertEquals(2, map.size());
        assertEquals(2, map.remove("two"));
        assertNull(map.get("two"));
        assertFalse(map.containsKey("two"));
        assertEquals(1, map.size());
    }

    @Test
    void putOnAnExistingKeyReplacesAndReturnsTheOldValue() {
        ChainedHashMap<String, Integer> map = new ChainedHashMap<>();
        map.put("k", 1);
        assertEquals(1, map.put("k", 2));
        assertEquals(2, map.get("k"));
        assertEquals(1, map.size(), "a replacement is not a new mapping");
    }

    @Test
    void removingAMissingKeyIsANoOp() {
        ChainedHashMap<String, Integer> map = new ChainedHashMap<>();
        map.put("a", 1);
        assertNull(map.remove("zzz"));
        assertEquals(1, map.size());
    }

    @Test
    void nullKeyAndNullValueAreSupported() {
        ChainedHashMap<String, String> map = new ChainedHashMap<>();
        map.put(null, "for-null-key");
        map.put("k", null);
        assertEquals("for-null-key", map.get(null));
        assertTrue(map.containsKey("k"), "a key mapped to null is present, though get() returns null for it");
        assertNull(map.get("k"));
        assertEquals("fallback", map.getOrDefault("absent", "fallback"));
        assertNull(map.getOrDefault("k", "fallback"), "present with a null value: the default must not apply");
        assertEquals("for-null-key", map.remove(null));
        assertFalse(map.containsKey(null));
    }

    @Test
    void clearForgetsEverything() {
        ChainedHashMap<Integer, Integer> map = new ChainedHashMap<>();
        for (int i = 0; i < 50; i++) {
            map.put(i, i);
        }
        map.clear();
        assertTrue(map.isEmpty());
        assertNull(map.get(7));
        assertFalse(map.iterator().hasNext());
        map.put(7, 70); // and it is still usable
        assertEquals(70, map.get(7));
    }

    // ------------------------------------------------------------------ sizing, by hand

    @ParameterizedTest(name = "initial capacity {0} -> {1} buckets")
    @CsvSource({"0, 1", "1, 1", "2, 2", "3, 4", "5, 8", "16, 16", "17, 32", "1000, 1024"})
    void capacityIsRoundedUpToAPowerOfTwo(int requested, int expected) {
        assertEquals(expected, new ChainedHashMap<String, String>(requested).capacity());
    }

    @Test
    void anAbsurdlyLargeRequestIsCappedNotWrapped() {
        assertEquals(ChainedHashMap.MAXIMUM_CAPACITY, ChainedHashMap.tableSizeFor(Integer.MAX_VALUE));
        assertEquals(ChainedHashMap.MAXIMUM_CAPACITY, ChainedHashMap.tableSizeFor(ChainedHashMap.MAXIMUM_CAPACITY + 1));
    }

    @Test
    void negativeCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChainedHashMap<String, String>(-1));
    }

    @Test
    void theThirteenthEntryOfADefaultMapDoublesTheTable() {
        // 16 buckets * 0.75 = 12: twelve entries fit, the thirteenth pushes size above the threshold.
        ChainedHashMap<Integer, Integer> map = new ChainedHashMap<>();
        for (int i = 0; i < 12; i++) {
            map.put(i, i);
        }
        assertEquals(16, map.capacity(), "12 entries: exactly at the threshold, not over it");
        map.put(12, 12);
        assertEquals(32, map.capacity());
        for (int i = 0; i < 13; i++) {
            assertEquals(i, map.get(i), "nothing may be lost or misplaced by the move");
        }
    }

    @Test
    void sequentialIntegerKeysSpreadOutWithoutAnyCollision() {
        ChainedHashMap<Integer, Integer> map = new ChainedHashMap<>();
        for (int i = 0; i < 10_000; i++) {
            map.put(i, i);
        }
        assertEquals(16_384, map.capacity()); // smallest power of two with 0.75 * capacity >= 10 000
        assertEquals(1, map.longestChain(), "an Integer hashes to itself, so 0..9999 fills distinct buckets");
    }

    // ------------------------------------------------------------------ hashing

    @Test
    void theHashFoldsTheHighBitsIntoTheLowOnes() {
        // 65536 = 1 << 16 and 0 differ only above bit 15, so both would index bucket 0 of any table shorter than
        // 65536 buckets. Folded, 65536 becomes 65536 ^ 1 = 65537, whose low bit is set: bucket 1 of a 16-bucket table.
        assertEquals(0, ChainedHashMap.hash(0));
        assertEquals(65_537, ChainedHashMap.hash(65_536));
        assertEquals(0, ChainedHashMap.hash(null));
        assertEquals(1, ChainedHashMap.indexFor(ChainedHashMap.hash(65_536), 16));
    }

    @Test
    void keysThatAllCollideStayCorrectButDegradeToOneLongChain() {
        ChainedHashMap<Colliding, Integer> map = new ChainedHashMap<>();
        int count = 200;
        for (int i = 0; i < count; i++) {
            map.put(new Colliding(i), i);
        }
        assertEquals(count, map.size());
        assertEquals(count, map.longestChain(), "no tree to fall back on: one bucket holds everything");
        for (int i = 0; i < count; i++) {
            assertEquals(i, map.get(new Colliding(i)));
        }
        assertEquals(50, map.remove(new Colliding(50)));
        assertNull(map.get(new Colliding(50)));
        assertEquals(count - 1, map.size());
    }

    // ------------------------------------------------------------------ iteration

    @Test
    void iterationVisitsEachEntryExactlyOnce() {
        ChainedHashMap<Integer, String> map = new ChainedHashMap<>();
        Map<Integer, String> expected = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            map.put(i * 31, "v" + i);
            expected.put(i * 31, "v" + i);
        }
        assertEquals(expected, snapshot(map));
    }

    @Test
    void iteratingAnEmptyMapEndsImmediately() {
        Iterator<ChainedHashMap.Entry<String, String>> it = new ChainedHashMap<String, String>().iterator();
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void structuralChangeDuringIterationIsReported() {
        ChainedHashMap<String, Integer> map = new ChainedHashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        Iterator<ChainedHashMap.Entry<String, Integer>> it = map.iterator();
        it.next();
        map.put("c", 3);
        assertThrows(ConcurrentModificationException.class, it::next);
    }

    @Test
    void replacingAValueDuringIterationIsAllowed() {
        ChainedHashMap<String, Integer> map = new ChainedHashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        Iterator<ChainedHashMap.Entry<String, Integer>> it = map.iterator();
        it.next();
        map.put("a", 99);
        it.next(); // no exception
    }

    @Test
    void forEachVisitsEverythingAndRejectsModificationFromTheCallback() {
        ChainedHashMap<Integer, Integer> map = new ChainedHashMap<>();
        for (int i = 0; i < 20; i++) {
            map.put(i, i * i);
        }
        Map<Integer, Integer> seen = new HashMap<>();
        map.forEach(seen::put);
        assertEquals(snapshot(map), seen);
        assertThrows(ConcurrentModificationException.class, () -> map.forEach((k, v) -> map.put(k + 1000, 0)));
    }

    // ------------------------------------------------------------------ against java.util.HashMap

    /**
     * The same random operations go to this map and to {@link HashMap}; every returned value must match, and at the
     * end both must hold exactly the same mappings. The key space is small (0..199) so that keys are overwritten,
     * removed and re-added often, which is where chain surgery goes wrong; null appears as a key and as a value.
     */
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 2026, 8675309})
    void behavesLikeHashMapUnderRandomOperations(long seed) {
        Random random = new Random(seed);
        ChainedHashMap<Integer, Integer> actual = new ChainedHashMap<>(random.nextInt(8));
        Map<Integer, Integer> oracle = new HashMap<>();

        for (int step = 0; step < 5000; step++) {
            Integer key = random.nextInt(40) == 0 ? null : random.nextInt(200);
            Integer value = random.nextInt(30) == 0 ? null : random.nextInt(1000);
            int operation = random.nextInt(100);

            if (operation < 45) {
                assertEquals(oracle.put(key, value), actual.put(key, value), "put at step " + step);
            } else if (operation < 70) {
                assertEquals(oracle.get(key), actual.get(key), "get at step " + step);
            } else if (operation < 90) {
                assertEquals(oracle.remove(key), actual.remove(key), "remove at step " + step);
            } else if (operation < 99) {
                assertEquals(oracle.containsKey(key), actual.containsKey(key), "containsKey at step " + step);
            } else {
                oracle.clear();
                actual.clear();
            }
            assertEquals(oracle.size(), actual.size(), "size at step " + step);
        }
        assertEquals(oracle, snapshot(actual));
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {4, 40, 400})
    void behavesLikeHashMapForStringKeys(long seed) {
        Random random = new Random(seed);
        ChainedHashMap<String, Integer> actual = new ChainedHashMap<>();
        Map<String, Integer> oracle = new HashMap<>();
        for (int step = 0; step < 3000; step++) {
            // Keys such as "Aa" and "BB" collide under String.hashCode, so real same-bucket distinct keys occur.
            String key = new String(new char[] {(char) ('A' + random.nextInt(3)), (char) ('a' + random.nextInt(3))});
            if (random.nextBoolean()) {
                assertEquals(oracle.put(key, step), actual.put(key, step));
            } else {
                assertEquals(oracle.remove(key), actual.remove(key));
            }
        }
        assertEquals(oracle, snapshot(actual));
    }

    @Test
    void stringKeysThatCollideUnderStringHashCodeAreKeptApart() {
        assertEquals("Aa".hashCode(), "BB".hashCode(), "premise: these two collide");
        ChainedHashMap<String, Integer> map = new ChainedHashMap<>();
        map.put("Aa", 1);
        map.put("BB", 2);
        assertEquals(1, map.get("Aa"));
        assertEquals(2, map.get("BB"));
        assertEquals(1, map.remove("Aa"));
        assertEquals(2, map.get("BB"));
    }
}
