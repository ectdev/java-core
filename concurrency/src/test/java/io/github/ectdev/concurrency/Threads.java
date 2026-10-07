package io.github.ectdev.concurrency;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

/** Small helpers for tests that must wait for a thread to reach a state instead of guessing how long that takes. */
final class Threads {

    private static final Set<Thread.State> BLOCKED =
        Set.of(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED);

    private Threads() { }

    /**
     * Returns once {@code thread} is parked (waiting on a condition or a lock). A fixed sleep before checking
     * "has it blocked yet?" is wrong in both directions: too short and the test is flaky, too long and it is slow.
     * Polling the thread's state waits exactly as long as needed.
     */
    static void awaitBlocked(Thread thread) {
        awaitState(thread, BLOCKED, "blocked");
    }

    static void awaitTerminated(Thread thread) {
        awaitState(thread, Set.of(Thread.State.TERMINATED), "terminated");
    }

    private static void awaitState(Thread thread, Set<Thread.State> wanted, String description) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!wanted.contains(thread.getState())) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("thread " + thread.getName() + " never became " + description
                    + " (state " + thread.getState() + ")");
            }
            LockSupport.parkNanos(200_000); // 0.2 ms
        }
    }
}
