package io.github.ectdev.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LruCacheTest {

    @Test
    void evictsTheLeastRecentlyUsedEntryWhenFull() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");     // a is now fresher than b
        cache.put("c", 3);  // over capacity: b is the least recently used
        assertNull(cache.get("b"));
        assertEquals(1, cache.get("a"));
        assertEquals(3, cache.get("c"));
        assertEquals(2, cache.size());
    }

    @Test
    void keysAreListedFromMostToLeastRecentlyUsed() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        assertEquals(List.of("c", "b", "a"), cache.keysMostRecentFirst());
        cache.get("a");
        assertEquals(List.of("a", "c", "b"), cache.keysMostRecentFirst());
        cache.put("b", 20); // an overwrite is a use as well
        assertEquals(List.of("b", "a", "c"), cache.keysMostRecentFirst());
    }

    @Test
    void overwritingReturnsTheOldValueAndNeverEvicts() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(1, cache.put("a", 10));
        assertEquals(2, cache.size());
        assertEquals(10, cache.get("a"));
        assertEquals(2, cache.get("b"));
    }

    @Test
    void aCapacityOfOneKeepsOnlyTheLatest() {
        LruCache<String, Integer> cache = new LruCache<>(1);
        cache.put("a", 1);
        cache.put("b", 2);
        assertNull(cache.get("a"));
        assertEquals(2, cache.get("b"));
        assertEquals(List.of("b"), cache.keysMostRecentFirst());
    }

    @Test
    void peekAndContainsKeyDoNotRefreshRecency() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(1, cache.peek("a"));
        assertTrue(cache.containsKey("a"));
        cache.put("c", 3); // a is still the eldest: peeking at it did not save it
        assertFalse(cache.containsKey("a"));
        assertEquals(List.of("c", "b"), cache.keysMostRecentFirst());
    }

    @Test
    void theEvictionCallbackSeesExactlyTheEntriesThatWereDropped() {
        List<String> evicted = new ArrayList<>();
        LruCache<String, Integer> cache = new LruCache<>(2, (k, v) -> evicted.add(k + "=" + v));
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3); // evicts a
        cache.get("b");
        cache.put("d", 4); // evicts c
        cache.remove("b"); // an explicit remove is not an eviction
        cache.clear();     // neither is clear
        assertEquals(List.of("a=1", "c=3"), evicted);
    }

    @Test
    void removeReturnsTheValueAndLeavesTheOthersOrdered() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        assertEquals(2, cache.remove("b"));
        assertNull(cache.remove("b"));
        assertEquals(List.of("c", "a"), cache.keysMostRecentFirst());
        cache.put("d", 4); // the freed slot is reusable: no eviction yet
        assertEquals(List.of("d", "c", "a"), cache.keysMostRecentFirst());
    }

    @Test
    void clearLeavesAUsableEmptyCache() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(List.of(), cache.keysMostRecentFirst());
        cache.put("b", 2);
        assertEquals(List.of("b"), cache.keysMostRecentFirst());
    }

    @Test
    void nullsAndBadCapacitiesAreRejected() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        assertThrows(NullPointerException.class, () -> cache.put(null, 1));
        assertThrows(NullPointerException.class, () -> cache.put("a", null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> new LruCache<String, Integer>(2, null));
        assertThrows(IllegalArgumentException.class, () -> new LruCache<String, Integer>(0));
        assertThrows(IllegalArgumentException.class, () -> new LruCache<String, Integer>(-3));
    }

    // ------------------------------------------------------------------ against LinkedHashMap

    /**
     * {@link LinkedHashMap} in access order, with {@code removeEldestEntry} bounding its size, is the JDK's own LRU
     * cache. The two must agree after every operation on three things: what a lookup returns, the recency order of
     * the keys, and which entry was evicted. The key space (0..11) is bigger than the capacity (5), so evictions
     * happen constantly.
     */
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 2026, 8675309})
    void matchesLinkedHashMapInAccessOrder(long seed) {
        final int capacity = 5;
        Random random = new Random(seed);

        List<Integer> oracleEvictions = new ArrayList<>();
        Map<Integer, Integer> oracle = new AccessOrderLru(capacity, oracleEvictions);

        List<Integer> evictions = new ArrayList<>();
        LruCache<Integer, Integer> actual = new LruCache<>(capacity, (key, value) -> evictions.add(key));

        for (int step = 0; step < 4000; step++) {
            int key = random.nextInt(12);
            int operation = random.nextInt(100);
            if (operation < 45) {
                int value = random.nextInt(1000);
                assertEquals(oracle.put(key, value), actual.put(key, value), "put at step " + step);
            } else if (operation < 85) {
                assertEquals(oracle.get(key), actual.get(key), "get at step " + step);
            } else if (operation < 95) {
                assertEquals(oracle.remove(key), actual.remove(key), "remove at step " + step);
            } else {
                assertEquals(oracle.containsKey(key), actual.containsKey(key), "containsKey at step " + step);
            }

            List<Integer> oracleOrder = new ArrayList<>(oracle.keySet()); // eldest first
            Collections.reverse(oracleOrder);                                // most recent first, as ours
            assertEquals(oracleOrder, actual.keysMostRecentFirst(), "recency order after step " + step);
            assertEquals(oracleEvictions, evictions, "evicted keys after step " + step);
        }
    }

    /** The JDK's own recipe for an LRU cache, recording which key it drops each time it is over capacity. */
    private static final class AccessOrderLru extends LinkedHashMap<Integer, Integer> {
        private static final long serialVersionUID = 1L;

        private final int capacity;
        private final transient List<Integer> evictedKeys;

        AccessOrderLru(int capacity, List<Integer> evictedKeys) {
            super(16, 0.75f, true); // true: iterate in access order, eldest first
            this.capacity = capacity;
            this.evictedKeys = evictedKeys;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
            boolean evict = size() > capacity;
            if (evict) {
                evictedKeys.add(eldest.getKey());
            }
            return evict;
        }
    }
}
