package io.github.tokennudge;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * Abstracts the notion of "loop iterations" and time for {@link VerificationEvaluator}, so
 * that awaiting logic can be unit-tested deterministically, without real threads, sleeps,
 * or timers.
 *
 * <p>The production implementation ({@code LoopIterationClock}, added together with
 * {@code io.github.tokennudge.internal.NudgeLoop}) wraps the loop's iteration counter and
 * condition variable, and {@link #nanoTime()} simply delegates to {@link System#nanoTime()}.
 *
 * <p>Not public API: package-private, since its only collaborators
 * ({@link VerificationEvaluator} and {@code TokenNudge}) live in this same package.
 */
interface IterationClock {

    /**
     * Returns whether the loop is currently running.
     *
     * <p>When {@code false}, {@link VerificationEvaluator} evaluates a verification
     * immediately, once, without waiting.
     *
     * @return {@code true} if the loop is running
     */
    boolean isRunning();

    /**
     * Returns the number of loop iterations completed so far.
     *
     * <p>Must be monotonically non-decreasing for a given clock instance.
     *
     * @return the completed iteration count
     */
    long iterationCount();

    /**
     * Returns whether the calling thread is the loop's own background thread.
     *
     * <p>When {@code true}, {@link VerificationEvaluator} evaluates the verification
     * immediately, once, exactly as if the loop were not running: the loop thread cannot
     * make any further iteration progress while it is itself blocked inside this very
     * {@code verify()} call, so waiting for a fresh iteration here would starve until the
     * timeout instead of ever genuinely observing one.
     *
     * @return {@code true} if the calling thread is the loop's own thread
     */
    boolean isCurrentThreadTheLoopThread();

    /**
     * Returns an iteration-count baseline suitable for awaiting a genuinely <em>fresh</em>
     * iteration: one guaranteed to have started (not merely finished) after this method is
     * called. Unlike {@link #iterationCount()} read on its own, this closes the gap where a
     * caller could otherwise observe an iteration that had already started before the call
     * as if it were "one full iteration that started after" it (used by
     * {@link VerificationEvaluator}'s non-monotonic, {@code never}/{@code atMost} awaiting).
     *
     * <p>Unlike an ordinary "current value" reading, this operation can genuinely fail to
     * produce a usable baseline within {@code timeout} (for example, an iteration is
     * holding the underlying lock for longer than the caller's whole budget). Callers must
     * treat {@link OptionalLong#empty()} as a timeout in its own right &mdash; evaluating
     * against a stale baseline instead would defeat the purpose of this method.
     *
     * @param timeout how long to wait for a fresh baseline to become available, never
     *                {@code null} or negative
     * @return the baseline iteration count, or {@link OptionalLong#empty()} if no fresh
     *         baseline could be obtained within {@code timeout}
     */
    OptionalLong freshIterationBaseline(Duration timeout);

    /**
     * Returns the current time in nanoseconds, per this clock. Mirrors
     * {@link System#nanoTime()}: only meaningful for computing elapsed time or deadlines
     * relative to another call to this method on the same instance.
     *
     * @return the current time, in nanoseconds
     */
    long nanoTime();

    /**
     * Blocks the calling thread until either the iteration count is greater than
     * {@code fromIteration}, or {@link #nanoTime()} reaches {@code deadlineNanos},
     * whichever happens first. Returns promptly once either condition holds; does not
     * throw on a timeout.
     *
     * <p>Implementations that block via an interruptible operation (for example
     * {@link Object#wait(long, int)}) must catch {@link InterruptedException} themselves,
     * restore the interrupt flag via {@code Thread.currentThread().interrupt()}, and return
     * normally, rather than propagating it &mdash; this method's signature declares no
     * checked exception.
     *
     * @param fromIteration the iteration count to wait for a change from
     * @param deadlineNanos the deadline, compared against {@link #nanoTime()}
     */
    void awaitIterationAfter(long fromIteration, long deadlineNanos);
}
