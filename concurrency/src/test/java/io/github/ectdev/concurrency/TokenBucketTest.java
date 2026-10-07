package io.github.ectdev.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TokenBucketTest {

    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    /** A clock the test sets by hand. Starting at a large odd value shows that only differences are used. */
    private static final class FakeClock {
        private final AtomicLong nanos = new AtomicLong(7_000_000_000_123L);

        long read() {
            return nanos.get();
        }

        void advance(long delta) {
            nanos.addAndGet(delta);
        }

        void set(long value) {
            nanos.set(value);
        }
    }

    private static TokenBucket bucket(long capacity, long perSecond, FakeClock clock) {
        return new TokenBucket(capacity, perSecond, clock::read);
    }

    // ------------------------------------------------------------------ hand-computed scenarios

    @Test
    void aFullBucketAllowsABurstOfExactlyItsCapacityThenRefuses() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(5, 10, clock);
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(1), "burst permit " + (i + 1));
        }
        assertFalse(bucket.tryAcquire(1), "the sixth, at the same instant");
    }

    @Test
    void atTenPerSecondOnePermitReturnsAfterExactlyOneHundredMilliseconds() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(5, 10, clock);
        assertTrue(bucket.tryAcquire(5)); // empty
        assertEquals(100 * MS, bucket.nanosUntilAvailable(1));

        clock.advance(100 * MS - 1);
        assertFalse(bucket.tryAcquire(1), "one nanosecond early");
        clock.advance(1);
        assertTrue(bucket.tryAcquire(1), "at exactly 100 ms");
        assertFalse(bucket.tryAcquire(1));
    }

    @Test
    void nothingIsLostToRoundingThreePermitsPerSecondYieldsThreeInExactlyOneSecond() {
        // One permit takes 1e9/3 = 333_333_333.33 ns. A limiter that rounds each wait loses or gains the fraction on
        // every permit; this one keeps it. By hand, in permit-nanoseconds (1 permit = 1e9, +3 per elapsed ns):
        //   after 333_333_334 ns:  3 * 333_333_334 = 1_000_000_002  -> take 1e9, 2 spare
        //   then 333_333_333 ns:   2 + 3 * 333_333_333 = 1_000_000_001 -> take 1e9, 1 spare
        //   then 333_333_333 ns:   1 + 3 * 333_333_333 = 1_000_000_000 -> take 1e9, 0 spare
        // so the three permits fall at 333_333_334, 666_666_667 and 1_000_000_000 ns: three per second, exactly.
        // The bucket is drained first and never refills to its capacity of 2, so nothing is clamped away.
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(2, 3, clock);
        assertTrue(bucket.tryAcquire(2));

        long start = clock.read();
        long[] arrivals = {333_333_334L, 666_666_667L, 1_000_000_000L};
        for (long arrival : arrivals) {
            clock.set(start + arrival - 1);
            assertFalse(bucket.tryAcquire(1), "one nanosecond before " + arrival);
            clock.set(start + arrival);
            assertTrue(bucket.tryAcquire(1), "exactly at " + arrival);
        }
        assertFalse(bucket.tryAcquire(1), "a fourth permit needs another third of a second");
    }

    @Test
    void aBucketThatFillsUpDiscardsTheSpareFractionBecauseIdleTimeWhileFullEarnsNothing() {
        // Same rate, capacity 1. The permit is complete at 333_333_333.33 ns but is taken at 333_333_334, and the
        // 0.67 ns in between was spent with the bucket full: that is lost, by design (it is what stops a bucket
        // banking idle time into a bigger burst). The next permit therefore takes a full 333_333_333.33 ns from the
        // moment of the take, which rounds up to 333_333_334: 333_333_334, 666_666_668, 1_000_000_002.
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(1, 3, clock);
        assertTrue(bucket.tryAcquire(1));

        long start = clock.read();
        long[] arrivals = {333_333_334L, 666_666_668L, 1_000_000_002L};
        for (long arrival : arrivals) {
            clock.set(start + arrival - 1);
            assertFalse(bucket.tryAcquire(1), "one nanosecond before " + arrival);
            clock.set(start + arrival);
            assertTrue(bucket.tryAcquire(1), "exactly at " + arrival);
        }
    }

    @Test
    void theWaitReportedMatchesWhenThePermitReallyArrives() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(1, 3, clock);
        assertTrue(bucket.tryAcquire(1));
        long wait = bucket.nanosUntilAvailable(1);
        assertEquals(333_333_334L, wait, "ceil(1e9 / 3)");
        clock.advance(wait - 1);
        assertFalse(bucket.tryAcquire(1));
        clock.advance(1);
        assertTrue(bucket.tryAcquire(1));
    }

    @Test
    void idleTimeBeyondFullEarnsNothing() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(5, 10, clock);
        clock.advance(60 * SECOND); // a minute idle, bucket already full
        assertEquals(5, bucket.availablePermits(), "no banking beyond the capacity");
        assertTrue(bucket.tryAcquire(5));
        assertFalse(bucket.tryAcquire(1), "an idle minute did not buy a bigger burst");
    }

    @Test
    void aPartiallyDrainedBucketRefillsOnlyToItsCapacity() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(10, 4, clock);
        assertTrue(bucket.tryAcquire(6));      // 4 left
        clock.advance(500 * MS);               // +2 permits at 4/s
        assertEquals(6, bucket.availablePermits());
        clock.advance(10 * SECOND);            // far more than the missing 4
        assertEquals(10, bucket.availablePermits());
    }

    @Test
    void anAcquireOfSeveralPermitsIsAllOrNothing() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(10, 10, clock);
        assertTrue(bucket.tryAcquire(8));      // 2 left
        assertFalse(bucket.tryAcquire(3), "needs 3, has 2");
        assertEquals(2, bucket.availablePermits(), "a refused request takes nothing");
        assertTrue(bucket.tryAcquire(2));
    }

    @Test
    void nanosUntilAvailableForSeveralPermits() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(10, 5, clock);
        assertTrue(bucket.tryAcquire(10)); // empty
        assertEquals(200 * MS, bucket.nanosUntilAvailable(1), "1 permit at 5/s = 200 ms");
        assertEquals(1_000 * MS, bucket.nanosUntilAvailable(5), "5 permits = 1 s");
        clock.advance(400 * MS); // 2 permits back
        assertEquals(600 * MS, bucket.nanosUntilAvailable(5), "3 still missing = 600 ms");
        assertEquals(0, bucket.nanosUntilAvailable(2), "available now");
    }

    // ------------------------------------------------------------------ the clock misbehaving

    @Test
    void aClockThatGoesBackwardsNeverTakesPermitsAway() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(5, 10, clock);
        assertTrue(bucket.tryAcquire(3)); // 2 left
        clock.advance(-5 * SECOND);
        assertEquals(2, bucket.availablePermits());
        // and it resumes from the new reading: 100 ms later, one permit more
        clock.advance(100 * MS);
        assertEquals(3, bucket.availablePermits());
    }

    @Test
    void aNanoTimeThatWrapsAroundLongMaxValueStillMeasuresTheRightInterval() {
        FakeClock clock = new FakeClock();
        clock.set(Long.MAX_VALUE - 50 * MS);
        TokenBucket bucket = bucket(5, 10, clock);
        assertTrue(bucket.tryAcquire(5));
        clock.advance(100 * MS); // wraps past Long.MAX_VALUE into negative readings
        assertTrue(clock.read() < 0, "premise: the reading wrapped");
        assertTrue(bucket.tryAcquire(1));
        assertFalse(bucket.tryAcquire(1));
    }

    @Test
    void anAstronomicalGapDoesNotOverflowTheArithmetic() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucket(TokenBucket.MAX_PARAMETER, TokenBucket.MAX_PARAMETER, clock);
        assertTrue(bucket.tryAcquire(TokenBucket.MAX_PARAMETER));
        clock.advance(Long.MAX_VALUE / 2); // ~146 years: elapsed * rate would overflow a long many times over
        assertEquals(TokenBucket.MAX_PARAMETER, bucket.availablePermits());
    }

    // ------------------------------------------------------------------ argument checks

    @ParameterizedTest
    @ValueSource(longs = {0, -1, TokenBucket.MAX_PARAMETER + 1, Long.MAX_VALUE})
    void capacityAndRateMustBeInRange(long bad) {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(bad, 1, () -> 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, bad, () -> 0));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 6, Long.MAX_VALUE})
    void permitsMustBeBetweenOneAndTheCapacity(long bad) {
        TokenBucket bucket = bucket(5, 1, new FakeClock());
        assertThrows(IllegalArgumentException.class, () -> bucket.tryAcquire(bad));
        assertThrows(IllegalArgumentException.class, () -> bucket.nanosUntilAvailable(bad));
    }

    @Test
    void aMissingClockIsRejected() {
        assertThrows(NullPointerException.class, () -> new TokenBucket(1, 1, null));
    }

    @Test
    void ceilDivRoundsUpExactly() {
        assertEquals(0, TokenBucket.ceilDiv(0, 7));
        assertEquals(1, TokenBucket.ceilDiv(1, 7));
        assertEquals(1, TokenBucket.ceilDiv(7, 7));
        assertEquals(2, TokenBucket.ceilDiv(8, 7));
        assertEquals(Long.MAX_VALUE, TokenBucket.ceilDiv(Long.MAX_VALUE, 1), "no overflow in the numerator + denominator - 1 trap");
    }

    // ------------------------------------------------------------------ threads

    @Test
    void underContentionNeverMorePermitsAreGrantedThanTheBucketHolds() throws InterruptedException {
        FakeClock clock = new FakeClock(); // frozen: no refill, so the answer is exact
        TokenBucket bucket = bucket(1_000, 1, clock);
        int threads = 16;
        AtomicLong granted = new AtomicLong();
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < 500; i++) { // 16 * 500 = 8,000 attempts for 1,000 permits
                    if (bucket.tryAcquire(1)) {
                        granted.incrementAndGet();
                    }
                }
            }, "acquirer-" + t);
            pool.add(worker);
        }
        pool.forEach(Thread::start);
        for (Thread worker : pool) {
            worker.join();
        }
        assertEquals(1_000, granted.get(), "exactly the capacity: not one more (a race) and not one fewer (a lost update)");
        assertEquals(0, bucket.availablePermits());
    }

    @Test
    void theSystemClockFactoryProducesABucketThatUsesRealTime() {
        TokenBucket bucket = TokenBucket.withSystemClock(3, 1);
        assertTrue(bucket.tryAcquire(3));
        long wait = bucket.nanosUntilAvailable(3); // three permits at 1/s: just under three seconds remain
        assertTrue(wait > 0 && wait <= 3 * SECOND, "wait was " + wait);
        assertEquals(3, bucket.capacity());
        assertEquals(1, bucket.refillPerSecond());
    }
}
