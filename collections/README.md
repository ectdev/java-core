# collections

A dynamic array, a hash map, an LRU cache and a binary heap, each written from scratch, and each checked
against the `java.util` class it imitates.

The point is not to replace the JDK. It is to see where the costs and the failure modes of everyday
data structures come from, by building them and then trying to break them. Use `ArrayList`, `HashMap`,
`LinkedHashMap` and `PriorityQueue` in real code.

| Class | What it is | Cost | JDK counterpart | What it leaves out |
| --- | --- | --- | --- | --- |
| [`DynamicArray<E>`](src/main/java/io/github/ectdev/collections/DynamicArray.java) | A resizable array | `add` amortised O(1); `get`, `set` O(1); insert or remove by index O(n) | `ArrayList` | The `List` interface, sublists, bulk operations |
| [`ChainedHashMap<K,V>`](src/main/java/io/github/ectdev/collections/ChainedHashMap.java) | A hash map with separate chaining | `put`, `get`, `remove` expected O(1), worst case O(n) | `HashMap` | Tree buckets, views, `computeIfAbsent` and friends |
| [`LruCache<K,V>`](src/main/java/io/github/ectdev/collections/LruCache.java) | A fixed-size cache that drops the least recently used entry | `get`, `put`, `remove` O(1) | `LinkedHashMap` in access order | Thread safety |
| [`MinHeap<E>`](src/main/java/io/github/ectdev/collections/MinHeap.java) | A binary min-heap | `add`, `poll` O(log n); `peek` O(1); build from a collection O(n) | `PriorityQueue` | `remove(Object)`, decrease-key, iteration |

Everything is documented in the Javadoc next to the code. The decisions that are easiest to get wrong are
collected here.

## Decisions worth reading

**Growth is geometric, so appending is cheap on average.** When `DynamicArray` is full it copies into an
array one and a half times the size (`old + old/2 + 1`; the `+ 1` lets a capacity of 0 or 1 grow at all).
The copies done over `n` appends form a geometric series that sums to less than `n * g / (g - 1)` elements
for growth factor `g`, which is 3n for 1.5. Growing by a fixed amount instead would make the same series
quadratic. The arithmetic runs in `long`, so near `Integer.MAX_VALUE` an overflow cannot wrap into a
negative size that would then be accepted; `newCapacity` is a pure function precisely so that this can be
tested without allocating two gigabytes.

**A hash map needs its bucket index to depend on all the bits.** The table length is a power of two, so a
bucket is `hash & (length - 1)`: cheap, but it only looks at the low bits. `Integer.hashCode()` is the value
itself, so 0 and 65536 would share a bucket in any table shorter than 65536 slots. `ChainedHashMap` first
folds the high half into the low half (`h ^ (h >>> 16)`), which is also what `HashMap` does. A test checks
the exact folded value, and another that 10,000 sequential integers end up with no collision at all.

**Leaving out the tree has a measurable price.** `HashMap` turns a long bucket into a red-black tree; this
class does not, so keys that all collide degrade every operation to O(n). A test builds such keys on purpose
and asserts both that the map stays correct and that the longest chain is the whole map. That is the
behaviour the JDK's extra machinery exists to avoid.

**Sentinels remove the edge cases from the linked list.** `LruCache` keeps its recency order in a doubly
linked list bracketed by a permanent head node and tail node. Every real node then has a neighbour on both
sides, so moving to the front and unlinking need no "is this the first or last node?" branch, which is where
hand-written linked lists usually go wrong.

**Building a heap is cheaper than adding n times.** Calling `add` n times costs O(n log n). Copying the
elements in and sifting down from the last parent to the root costs O(n), because most nodes are near the
bottom, where sifting down does almost nothing.

**Iterators fail fast.** Every structural change bumps a counter that the iterator compares with its own
copy. A change made behind its back throws `ConcurrentModificationException` instead of silently skipping
or repeating elements. Replacing a value in place is not structural, and is allowed.

**A removed element must not stay reachable.** `DynamicArray.remove` and `clear` null out the vacated slots.
Without that, the array keeps the object alive after the list has forgotten it. A package-private hook lets a
test look at the slot directly, which is deterministic where a garbage-collection test would not be.

## How it is checked

* **Hand-computed answers.** Capacities, thresholds and growth steps are written out as tables whose rows
  were worked on paper first: `newCapacity(10, 11) = 16`, the thirteenth insertion into a default map
  doubles it from 16 to 32, a cache of capacity 2 that sees `a`, `b`, a read of `a`, then `c` evicts `b`.
* **Differential tests against the JDK.** Random sequences of operations are applied to the class and to its
  `java.util` counterpart, and every returned value must match at every step; error paths are included by
  letting indexes stray outside the valid range. `LruCache` is compared with a `LinkedHashMap` in access
  order on the lookup result, the whole recency order and the key evicted at each step. All seeds are fixed,
  so a failure reproduces.
* **An invariant checked after every operation.** The heap's only rule, that no element is smaller than its
  parent, is verified after each of thousands of random operations, not just at the end, because a heap can
  return correct answers for a long time while already broken. The checker itself is tested by flipping the
  comparator under a valid heap and requiring it to report the damage.
* **Mutation checks.** The tests were run against deliberately broken copies of the code (see
  [`tools/`](../tools/README.md)). One survivor in the first round was a real gap: nothing exercised
  fail-fast iteration after an insert by index, a remove or a clear. It now does.

Run just this module with `./mvnw -pl collections test`.
