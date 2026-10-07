package io.github.ectdev.collections;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * A hash map with separate chaining: the core of {@code java.util.HashMap}, without the parts that exist
 * only to survive hostile input.
 *
 * <p>The table length is always a power of two, so a bucket is chosen with {@code hash & (length - 1)} instead of a
 * division. That only works if the low bits of the hash carry information, and {@code hashCode()} implementations
 * often put theirs in the high bits (an {@code Integer} 65536 apart from 0 differs only in bit 16), so the hash is
 * first folded: {@code h ^ (h >>> 16)}.
 *
 * <p>The table doubles when {@code size > 0.75 * capacity}. Every entry is then moved to its new bucket, an O(n)
 * step that is paid for by the n insertions before it: puts and gets are expected O(1).
 *
 * <p><b>What it does not do.</b> {@code HashMap} turns a bucket that grows past eight entries into a red-black tree,
 * so a flood of colliding keys costs O(log n) per lookup. Here a bucket stays a linked list, and keys that all
 * collide make every operation O(n). That is the honest price of leaving the tree out; see
 * {@link #longestChain()}.
 *
 * <p>Not thread-safe. Both keys and values may be {@code null}. Iteration order is unspecified.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class ChainedHashMap<K, V> implements Iterable<ChainedHashMap.Entry<K, V>> {

    /** An immutable snapshot of one mapping, as seen by an iterator. */
    public record Entry<K, V>(K key, V value) { }

    static final int DEFAULT_CAPACITY = 16;
    static final int MAXIMUM_CAPACITY = 1 << 30;

    private static final class Node<K, V> {
        final int hash;
        final K key;
        V value;
        Node<K, V> next;

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private Node<K, V>[] table;
    private int size;
    private int threshold;
    private int modCount;

    public ChainedHashMap() {
        this(DEFAULT_CAPACITY);
    }

    /** @param initialCapacity rounded up to the next power of two (0 and 1 both give 1) */
    public ChainedHashMap(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + initialCapacity);
        }
        this.table = newTable(tableSizeFor(initialCapacity));
        this.threshold = thresholdFor(table.length);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Associates {@code value} with {@code key}; returns the previous value, or {@code null} if there was none. */
    public V put(K key, V value) {
        int hash = hash(key);
        int index = indexFor(hash, table.length);
        for (Node<K, V> node = table[index]; node != null; node = node.next) {
            if (node.hash == hash && Objects.equals(node.key, key)) {
                V old = node.value;
                node.value = value; // replacing a value is not a structural change
                return old;
            }
        }
        table[index] = new Node<>(hash, key, value, table[index]);
        modCount++;
        if (++size > threshold) {
            resize();
        }
        return null;
    }

    /** The value for {@code key}, or {@code null} if there is no mapping (use {@link #containsKey} to tell them apart). */
    public V get(Object key) {
        Node<K, V> node = find(key);
        return node == null ? null : node.value;
    }

    public V getOrDefault(Object key, V defaultValue) {
        Node<K, V> node = find(key);
        return node == null ? defaultValue : node.value;
    }

    public boolean containsKey(Object key) {
        return find(key) != null;
    }

    /** Removes the mapping for {@code key} and returns its value, or {@code null} if there was none. */
    public V remove(Object key) {
        int hash = hash(key);
        int index = indexFor(hash, table.length);
        Node<K, V> previous = null;
        for (Node<K, V> node = table[index]; node != null; previous = node, node = node.next) {
            if (node.hash == hash && Objects.equals(node.key, key)) {
                if (previous == null) {
                    table[index] = node.next;
                } else {
                    previous.next = node.next;
                }
                size--;
                modCount++;
                return node.value;
            }
        }
        return null;
    }

    public void clear() {
        Arrays.fill(table, null);
        size = 0;
        modCount++;
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        int expected = modCount;
        for (Node<K, V> head : table) {
            for (Node<K, V> node = head; node != null; node = node.next) {
                action.accept(node.key, node.value);
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
        return new EntryIterator();
    }

    // ------------------------------------------------------------------ internals

    private Node<K, V> find(Object key) {
        int hash = hash(key);
        for (Node<K, V> node = table[indexFor(hash, table.length)]; node != null; node = node.next) {
            if (node.hash == hash && Objects.equals(node.key, key)) {
                return node;
            }
        }
        return null;
    }

    /** Folds the high half of the hash into the low half, so that power-of-two masking still sees it. */
    static int hash(Object key) {
        if (key == null) {
            return 0;
        }
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    static int indexFor(int hash, int tableLength) {
        return hash & (tableLength - 1);
    }

    /** The smallest power of two that is at least {@code capacity}, capped at {@link #MAXIMUM_CAPACITY}. */
    static int tableSizeFor(int capacity) {
        if (capacity <= 1) {
            return 1;
        }
        if (capacity >= MAXIMUM_CAPACITY) {
            return MAXIMUM_CAPACITY;
        }
        return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(capacity - 1));
    }

    private static int thresholdFor(int capacity) {
        return capacity == MAXIMUM_CAPACITY ? Integer.MAX_VALUE : (int) (capacity * 0.75);
    }

    @SuppressWarnings("unchecked") // a generic array cannot be created directly; every slot only ever holds Node<K, V>
    private static <K, V> Node<K, V>[] newTable(int length) {
        return (Node<K, V>[]) new Node<?, ?>[length];
    }

    private void resize() {
        Node<K, V>[] old = table;
        if (old.length == MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE; // cannot grow any further; stop asking
            return;
        }
        Node<K, V>[] grown = newTable(old.length << 1);
        for (Node<K, V> head : old) {
            Node<K, V> node = head;
            while (node != null) {
                Node<K, V> following = node.next; // read before relinking
                int index = indexFor(node.hash, grown.length);
                node.next = grown[index];
                grown[index] = node;
                node = following;
            }
        }
        table = grown;
        threshold = thresholdFor(grown.length);
    }

    // ------------------------------------------------------------------ test hooks

    /** The number of buckets. */
    int capacity() {
        return table.length;
    }

    /** The length of the longest bucket: 1 for a well-spread table, {@code size} when every key collides. */
    int longestChain() {
        int longest = 0;
        for (Node<K, V> head : table) {
            int length = 0;
            for (Node<K, V> node = head; node != null; node = node.next) {
                length++;
            }
            longest = Math.max(longest, length);
        }
        return longest;
    }

    // ------------------------------------------------------------------ iterator

    private final class EntryIterator implements Iterator<Entry<K, V>> {
        private int bucket;
        private Node<K, V> next;
        private final int expectedModCount = modCount;

        EntryIterator() {
            advance(null);
        }

        /** Moves {@code next} to the node after {@code current}, scanning on to the following buckets as needed. */
        private void advance(Node<K, V> current) {
            Node<K, V> candidate = current == null ? null : current.next;
            while (candidate == null && bucket < table.length) {
                candidate = table[bucket++];
            }
            next = candidate;
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Entry<K, V> next() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (next == null) {
                throw new NoSuchElementException();
            }
            Node<K, V> node = next;
            advance(node);
            return new Entry<>(node.key, node.value);
        }
    }
}
