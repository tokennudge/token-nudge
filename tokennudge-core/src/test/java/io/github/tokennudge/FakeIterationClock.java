package io.github.tokennudge;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Deterministic, non-blocking {@link IterationClock} test fixture: instead of sleeping,
 * each call to {@link #awaitIterationAfter(long, long)} either runs one pre-scripted
 * "what happens during this iteration" action (and bumps the iteration count), or, if
 * nothing is scripted, jumps its virtual clock straight to the deadline. This lets
 * {@link VerificationEvaluator} tests exercise the full await/timeout logic synchronously,
 * with no real threads, sleeps, or wall-clock time involved.
 */
final class FakeIterationClock implements IterationClock {

    private boolean running = true;
    private long iteration = 0;
    private long nanoTime = 0;
    private final Deque<Runnable> scriptedIterations = new ArrayDeque<>();

    FakeIterationClock() {
    }

    FakeIterationClock(long initialNanoTime) {
        this.nanoTime = initialNanoTime;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public long iterationCount() {
        return iteration;
    }

    @Override
    public boolean isCurrentThreadTheLoopThread() {
        // This fake never involves a real background thread; every scripted "iteration"
        // runs synchronously on the calling thread, so there is no separate loop thread to
        // ever be considered "the calling thread" from the evaluator's point of view.
        return false;
    }

    @Override
    public long freshIterationBaseline(Duration timeout) {
        // No real concurrency here: the current iteration count already reflects every
        // iteration that has been "run" (scripted) so far, so it aliases iterationCount().
        return iteration;
    }

    @Override
    public long nanoTime() {
        return nanoTime;
    }

    @Override
    public void awaitIterationAfter(long fromIteration, long deadlineNanos) {
        if (!scriptedIterations.isEmpty()) {
            scriptedIterations.poll().run();
            iteration++;
            return;
        }
        if (nanoTime < deadlineNanos) {
            nanoTime = deadlineNanos;
        }
    }

    void setRunning(boolean running) {
        this.running = running;
    }

    /**
     * Queues an action to run "during" the next awaited iteration (for example appending
     * journal entries), after which the iteration count is bumped.
     */
    void queueIteration(Runnable duringIteration) {
        scriptedIterations.add(duringIteration);
    }
}
