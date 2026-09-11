package io.github.tokennudge;

import io.github.tokennudge.internal.NudgeLoop;

import java.time.Duration;
import java.util.Objects;

/**
 * Adapts an {@code io.github.tokennudge.internal.NudgeLoop}'s public
 * {@code isRunning()}/{@code iterationCount()}/{@code awaitIterationAfter(...)} methods to
 * the {@link IterationClock} contract used by {@link VerificationEvaluator}.
 *
 * <p>{@link #nanoTime()} delegates directly to {@link System#nanoTime()}, since it is a
 * plain clock reading, not something the loop itself needs to track.
 */
final class LoopIterationClock implements IterationClock {

    private final NudgeLoop loop;

    LoopIterationClock(NudgeLoop loop) {
        this.loop = Objects.requireNonNull(loop, "loop must not be null");
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }

    @Override
    public long iterationCount() {
        return loop.iterationCount();
    }

    @Override
    public boolean isCurrentThreadTheLoopThread() {
        return loop.isCurrentThreadTheLoopThread();
    }

    @Override
    public long freshIterationBaseline(Duration timeout) {
        return loop.freshIterationBaseline(timeout);
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public void awaitIterationAfter(long fromIteration, long deadlineNanos) {
        loop.awaitIterationAfter(fromIteration, deadlineNanos);
    }
}
