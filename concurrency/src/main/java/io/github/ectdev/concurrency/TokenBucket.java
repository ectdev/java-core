package io.github.ectdev.concurrency;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A token-bucket rate limiter: a bucket holds up to {@code capacity} permits and refills at a steady
 * {@code refillPerSecond}; an operation takes permits out, and is refused if there are not enough.
 *
 * <p>The two numbers mean different things. The refill rate is the long-run average rate that is allowed. The
 * capacity is the burst that is allowed on top of it: a full bucket lets {@code capacity} operations through at
 * once, after which the caller is held to the refill rate.
 *
 * <h2>No clock of its own</h2>
 * The time source is a {@link LongSupplier} of nanoseconds, passed in. Production code gives it
 * {@code System::nanoTime} (see {@link #withSystemClock}); a test gives it a number it can set, so the
 * boundaries below can be tested exactly and instantly, with no sleeping and no flakiness.
 *
 * <h2>Exact arithmetic, no drift</h2>
 * The obvious implementation counts whole tokens and, on each refill, rounds the elapsed time to a token. That
 * either loses the fractional remainder (the limiter runs slow) or carries it with a rounding error (it drifts
 * fast). Here the bucket's contents are stored in <em>permit-nanoseconds</em>: one permit is
 * {@code 1_000_000_000} of them, and every elapsed nanosecond adds exactly {@code refillPerSecond} of them. All
 * arithmetic is integer and nothing is rounded away, so at 3 permits per second three permits arrive in exactly
 * one second, to the nanosecond, however often the bucket is asked in between.
 *
 * <p>Two refinements keep it honest. Time spent while the bucket is full earns nothing: you cannot bank idle
 * time beyond the capacity. And a clock reading that goes backwards is ignored rather than being allowed to take
 * permits away.
 *
 * <p>Thread-safe: every method is {@code synchronized}, and each one reads the clock once.
 */
public final class TokenBucket {

    private static final long NANOS_PER_PERMIT_UNIT = 1_000_000_000L;

    /** Upper bound on capacity and rate, chosen so that {@code capacity * 1e9} still fits in a {@code long}. */
    public static final long MAX_PARAMETER = 1_000_000_000L;

    private final long capacity;
    private final long capacityNanos;       // capacity, in permit-nanoseconds
    private final long refillPerSecond;     // also: permit-nanoseconds gained per elapsed nanosecond
    private final LongSupplier nanoClock;

    private long storedNanos;               // current contents, in permit-nanoseconds
    private long lastReading;

    /**
     * Creates a bucket that starts full.
     *
     * @param capacity        the most permits it can hold, from 1 to {@link #MAX_PARAMETER}
     * @param refillPerSecond permits gained per second, from 1 to {@link #MAX_PARAMETER}
     * @param nanoClock       a monotonic source of nanoseconds (only differences between readings are used)
     */
    public TokenBucket(long capacity, long refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || capacity > MAX_PARAMETER) {
            throw new IllegalArgumentException("capacity must be between 1 and " + MAX_PARAMETER + ": " + capacity);
        }
        if (refillPerSecond < 1 || refillPerSecond > MAX_PARAMETER) {
            throw new IllegalArgumentException(
                "refillPerSecond must be between 1 and " + MAX_PARAMETER + ": " + refillPerSecond);
        }
        this.capacity = capacity;
        this.capacityNanos = capacity * NANOS_PER_PERMIT_UNIT;
        this.refillPerSecond = refillPerSecond;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.storedNanos = capacityNanos;
        this.lastReading = nanoClock.getAsLong();
    }

    /** A bucket driven by {@link System#nanoTime()}. */
    public static TokenBucket withSystemClock(long capacity, long refillPerSecond) {
        return new TokenBucket(capacity, refillPerSecond, System::nanoTime);
    }

    /** Takes {@code permits} if that many are available right now; otherwise takes nothing and returns false. */
    public synchronized boolean tryAcquire(long permits) {
        long needed = requireValid(permits);
        refill();
        if (storedNanos < needed) {
            return false;
        }
        storedNanos -= needed;
        return true;
    }

    /**
     * How long until {@code permits} could be acquired, in nanoseconds: 0 if they are available now. This is a
     * lower bound under the assumption that nobody else takes permits in the meantime.
     */
    public synchronized long nanosUntilAvailable(long permits) {
        long needed = requireValid(permits);
        refill();
        long missing = needed - storedNanos;
        return missing <= 0 ? 0 : ceilDiv(missing, refillPerSecond);
    }

    /** The number of whole permits available right now. */
    public synchronized long availablePermits() {
        refill();
        return storedNanos / NANOS_PER_PERMIT_UNIT;
    }

    public long capacity() {
        return capacity;
    }

    public long refillPerSecond() {
        return refillPerSecond;
    }

    // ------------------------------------------------------------------ internals (lock held)

    private long requireValid(long permits) {
        if (permits < 1 || permits > capacity) {
            throw new IllegalArgumentException(
                "permits must be between 1 and the capacity (" + capacity + "): " + permits);
        }
        return permits * NANOS_PER_PERMIT_UNIT;
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastReading; // a difference of two nanoTime readings is valid even across a wrap
        lastReading = now;
        if (elapsed <= 0) {
            return; // no time passed, or the clock went backwards: never take permits away
        }
        long missing = capacityNanos - storedNanos;
        // If the elapsed time is enough to fill the bucket, say so without multiplying: elapsed * refillPerSecond
        // could overflow, and anything beyond a full bucket is discarded anyway.
        if (elapsed >= ceilDiv(missing, refillPerSecond)) {
            storedNanos = capacityNanos;
        } else {
            storedNanos += elapsed * refillPerSecond; // strictly less than `missing`: neither overflows nor overfills
        }
    }

    /** {@code ceil(numerator / denominator)} for a non-negative numerator and a positive denominator. */
    static long ceilDiv(long numerator, long denominator) {
        return numerator == 0 ? 0 : (numerator - 1) / denominator + 1;
    }
}
