# concurrency

A bounded blocking buffer, a token-bucket rate limiter and a compute-once memoizer, built on locks and
conditions rather than on the ready-made classes of `java.util.concurrent`, and tested the way concurrent
code has to be tested: without sleeping, without trusting luck.

| Class | What it is | JDK counterpart | What it teaches |
| --- | --- | --- | --- |
| [`BoundedBuffer<E>`](src/main/java/io/github/ectdev/concurrency/BoundedBuffer.java) | A fixed-size FIFO queue; producers wait when it is full, consumers when it is empty | `ArrayBlockingQueue` | One lock, two conditions; waiting in a loop; timeouts; interruption |
| [`TokenBucket`](src/main/java/io/github/ectdev/concurrency/TokenBucket.java) | A rate limiter that allows a steady rate plus a bounded burst | (Guava's `RateLimiter`, not in the JDK) | Injecting a clock; exact integer arithmetic; what "burst" means |
| [`Memoizer<K,V>`](src/main/java/io/github/ectdev/concurrency/Memoizer.java) | A cache that computes each key once, however many threads ask at the same time | `ConcurrentHashMap.computeIfAbsent` | Why check-then-act is a bug; publishing a future before doing the work |

## Decisions worth reading

**One lock, two conditions.** `BoundedBuffer` guards all its state with one `ReentrantLock` and gives it two
`Condition`s, one for each question a thread can be waiting on: "is there room?" and "is there something to
take?". A `put` then wakes a consumer and never a producer, and since each event satisfies exactly one waiter
it can use `signal()` rather than `signalAll()`, which wakes every waiter just to send all but one back to sleep.

**Waiting is a loop, and the clock comes second.** Three rules, all visible in the code:

1. `await` sits inside `while (full)`, never `if`. A thread can wake without its condition being true: spurious
   wakeups are allowed, and another thread may have taken the slot first.
2. In the timed methods the state is tested *before* the deadline. A consumer woken at the very instant its time
   runs out must still take an element that is there; checking the clock first would send it away empty-handed
   with an element sitting in the queue. A zero timeout therefore means "try once", not "refuse".
3. `awaitNanos` returns the time that is left and that is what the next wait receives, so repeated wakeups cannot
   stretch a one-second timeout into several.

**A rate limiter should not own a clock.** `TokenBucket` takes its time as a `LongSupplier` of nanoseconds.
Production passes `System::nanoTime`; a test passes a number it can set, so every boundary is tested exactly
and instantly. The tests would otherwise need to sleep, and a test that sleeps either is slow or is flaky.

**Counting in permit-nanoseconds makes the arithmetic exact.** The natural implementation counts whole tokens and
rounds the elapsed time to a token on each refill, which either loses the fraction (the limiter runs slow) or
carries it with an error (it drifts fast). Here the bucket's contents are stored in permit-nanoseconds: one permit
is 10⁹ of them and every elapsed nanosecond adds exactly `refillPerSecond`. Everything is integer arithmetic and
nothing is rounded away. At 3 permits per second the three permits of a second fall at 333,333,334, 666,666,667
and 1,000,000,000 ns, and a test checks each of them one nanosecond before and at the instant. The same
representation gives `nanosUntilAvailable` as a single ceiling division.

**Idle time while the bucket is full earns nothing.** This is what makes the capacity a *burst* limit and not a
savings account. It has a visible consequence worth knowing: with capacity 1 at 3 permits per second, the
permits fall at 333,333,334, 666,666,668 and 1,000,000,002 ns, not at the exact thirds, because the fraction of a
nanosecond that passed while the bucket was full and waiting to be taken is discarded. Both behaviours have a test
and a comment that works the numbers out, so neither can be "fixed" by accident.

**Defensive details with a test each.** A clock reading that goes backwards is ignored rather than taking permits
away. A `nanoTime` that wraps past `Long.MAX_VALUE` still measures the right interval, because only differences
are used. A gap of 146 years does not overflow, because the elapsed time is compared with the time needed to fill
the bucket before anything is multiplied.

**A cache of futures, because each simpler version is wrong in its own way.** A synchronized method around a map
serialises unrelated keys. A concurrent map with `if (!contains) put(compute())` is fast and wrong: two threads
both see the key missing and both compute it, because the check and the put are each atomic and together are not.
`Memoizer` stores a `FutureTask` in the map *before* the work starts, using the atomic `putIfAbsent`: exactly one
thread wins the right to run it, every other thread waits for the same future, and different keys run in
parallel. A failed computation is removed so that the next caller retries; the removal is conditional, so it can
only remove the failed task and never a newer one a faster thread has put in its place.

## How it is checked

* **A fake clock for everything with time in it.** The `TokenBucket` scenarios are worked out by hand and written as
  tables: a bucket that refills at 10 permits per second gets a permit back after exactly 100 ms, tested at
  99,999,999 ns and at 100,000,000.
* **Waiting for a thread to block, not guessing how long that takes.** Tests that need "the producer is now stuck"
  poll `Thread.getState()` until it is `WAITING`, instead of sleeping and hoping. Too short a sleep makes the test
  flaky, too long makes it slow.
* **A conservation property under real contention.** Four producers and four consumers push 20,000 elements
  through a three-slot buffer: every element must be delivered exactly once, none invented, and each consumer must
  see any one producer's elements in the order that producer put them. Sixteen threads hammer a bucket with a frozen
  clock and exactly its capacity must be granted: not one more (a race) and not one fewer (a lost update).
* **A harness that is shown to be able to fail.** The race test for `Memoizer` is run first against a deliberately
  broken check-then-act memoizer, and must see it compute twice. A passing run of the real one only means something
  because the same harness fails on the bad one. The harness is deterministic: a computation holds itself open
  until a second thread also starts computing, so the bug cannot hide behind lucky timing.
* **A timeout on every test.** A concurrency bug usually shows up as waiting forever. Each test has a 30-second
  limit (see `src/test/resources/junit-platform.properties`), so a lost wakeup fails the build instead of hanging it.
* **Mutation checks.** Twenty-four deliberately broken copies (a `while` turned into an `if`, a `signal()` deleted,
  a lock removed, the clamp moved by one, the check-then-act version of the cache) are all caught; see
  [`tools/`](../tools/README.md). The first round found a real gap: only `take` had an interruption test, not a
  `put` blocked on a full buffer.

The suite was also run 25 times in a row, ten of them with sixteen busy-loop threads competing for an eight-core
machine, without a failure.

## What is not covered, and what a test cannot show

A concurrency test can only ever fail; a green run is the absence of a failure found, not a proof. The stress
tests catch bugs that are likely to occur, not every interleaving.

One property is argued in the Javadoc but not tested: that `Memoizer` removes a failed entry *conditionally*
(`remove(key, task)`). Showing the difference needs a thread to be held between receiving a failure and removing
the entry, at exactly the moment another thread publishes a new task, and nothing in the public API allows that to
be forced. `BoundedBuffer` is not fair (waiting threads are not served in arrival order) and has no `close`;
`TokenBucket` has no blocking `acquire`, only `tryAcquire` and the time to wait, so the caller chooses how to wait.

Run just this module with `./mvnw -pl concurrency test`.
