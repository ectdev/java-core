package io.github.ectdev.collections;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * A fixed-capacity cache that evicts the least recently used entry.
 *
 * <p>Two structures cooperate: a hash map from key to node gives O(1) lookup, and a doubly linked list orders the
 * nodes from most to least recently used, so that moving a node to the front and dropping the one at the back are
 * both O(1) too. Neither alone could do it: a hash map has no order, and a list cannot find a key without scanning.
 *
 * <p>The list is bracketed by two sentinel nodes, {@code head} and {@code tail}, which never hold data. With them
 * every real node has a neighbour on both sides, so insertion and unlinking need no special case for "first" or
 * "last", which is where hand-written linked lists usually break.
 *
 * <p>What counts as a use: {@link #get} and {@link #put} refresh an entry. {@link #peek}, {@link #containsKey} and
 * {@link #keysMostRecentFirst()} deliberately do not, so that inspecting the cache does not change what it evicts.
 *
 * <p>Not thread-safe; wrap it in a lock, or use one cache per thread. Keys and values must not be {@code null}.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class LruCache<K, V> {

    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> previous;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> index = new HashMap<>();
    private final Node<K, V> head = new Node<>(null, null); // head.next is the most recently used
    private final Node<K, V> tail = new Node<>(null, null); // tail.previous is the least recently used
    private final BiConsumer<? super K, ? super V> onEvict;

    public LruCache(int capacity) {
        this(capacity, (key, value) -> { });
    }

    /**
     * @param onEvict called with each entry the cache drops to make room (not for {@link #remove} or {@link #clear});
     *                it runs inside {@link #put}, so it must not call back into this cache
     */
    public LruCache(int capacity, BiConsumer<? super K, ? super V> onEvict) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        this.capacity = capacity;
        this.onEvict = Objects.requireNonNull(onEvict, "onEvict");
        head.next = tail;
        tail.previous = head;
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return index.size();
    }

    /** The value for {@code key}, or {@code null}; a hit makes the entry the most recently used. */
    public V get(K key) {
        Node<K, V> node = index.get(Objects.requireNonNull(key, "key"));
        if (node == null) {
            return null;
        }
        moveToFront(node);
        return node.value;
    }

    /** Like {@link #get}, but leaves the recency order alone. */
    public V peek(K key) {
        Node<K, V> node = index.get(Objects.requireNonNull(key, "key"));
        return node == null ? null : node.value;
    }

    public boolean containsKey(K key) {
        return index.containsKey(Objects.requireNonNull(key, "key"));
    }

    /**
     * Stores {@code value} and makes the entry the most recently used. If the cache is then over capacity the least
     * recently used entry is evicted and passed to the eviction callback.
     *
     * @return the value this key had before, or {@code null}
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Node<K, V> existing = index.get(key);
        if (existing != null) {
            V old = existing.value;
            existing.value = value;
            moveToFront(existing);
            return old;
        }
        Node<K, V> node = new Node<>(key, value);
        index.put(key, node);
        linkAtFront(node);
        if (index.size() > capacity) {
            Node<K, V> eldest = tail.previous;
            unlink(eldest);
            index.remove(eldest.key);
            onEvict.accept(eldest.key, eldest.value);
        }
        return null;
    }

    /** Removes the entry for {@code key} without calling the eviction callback; returns its value, or {@code null}. */
    public V remove(K key) {
        Node<K, V> node = index.remove(Objects.requireNonNull(key, "key"));
        if (node == null) {
            return null;
        }
        unlink(node);
        return node.value;
    }

    public void clear() {
        index.clear();
        head.next = tail;
        tail.previous = head;
    }

    /** The keys from the most recently used to the least recently used. Does not count as a use. */
    public List<K> keysMostRecentFirst() {
        List<K> keys = new ArrayList<>(index.size());
        for (Node<K, V> node = head.next; node != tail; node = node.next) {
            keys.add(node.key);
        }
        return keys;
    }

    // ------------------------------------------------------------------ list surgery

    private void moveToFront(Node<K, V> node) {
        if (head.next == node) {
            return;
        }
        unlink(node);
        linkAtFront(node);
    }

    private void linkAtFront(Node<K, V> node) {
        node.previous = head;
        node.next = head.next;
        head.next.previous = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
        node.previous.next = node.next;
        node.next.previous = node.previous;
        node.previous = null;
        node.next = null;
    }
}
