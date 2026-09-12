package io.github.tokennudge.internal;

import io.github.tokennudge.Simulation;
import io.github.tokennudge.TokenNudge;
import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.spi.ClaimResult;
import io.github.tokennudge.spi.DiscoveryQuery;
import io.github.tokennudge.spi.EngineAdapter;
import io.github.tokennudge.testsupport.FakeEngineAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static io.github.tokennudge.TokenNudge.externalTask;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link NudgeLoop} against {@link FakeEngineAdapter}, driving the loop's real
 * background thread and awaiting deterministically via {@link NudgeLoop#awaitIterationAfter}
 * instead of fixed sleeps.
 */
class NudgeLoopTest {

    private static final String TOPIC = "charge-card";
    private static final Duration SHORT_POLL = Duration.ofMillis(20);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    private final FakeEngineAdapter adapter = new FakeEngineAdapter();
    private final SimulationRegistry registry = new SimulationRegistry();
    private final InMemoryJournal journal = new InMemoryJournal();
    private NudgeLoop loop;

    @AfterEach
    void stopLoop() {
        if (loop != null) {
            loop.stop(Duration.ofSeconds(2));
        }
    }

    private NudgeLoop newLoop(Duration pollInterval, boolean captureVariables) {
        loop = new NudgeLoop(adapter, registry, journal, new ReentrantLock(), pollInterval, captureVariables, 50);
        return loop;
    }

    private static void awaitUntil(NudgeLoop loop, BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            loop.awaitIterationAfter(loop.iterationCount(), deadline);
        }
    }

    @Test
    void completesMatchingWaitStateAndJournalsHandledWithVariablesSnapshot() {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState, Map.of("amount", 4200));
        registry.add(externalTask(TOPIC).willComplete(withVariables(Map.of("paid", true))));

        NudgeLoop loop = newLoop(SHORT_POLL, true);
        loop.start();

        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);

        List<JournalEntry> entries = journal.entries();
        assertThat(entries).hasSize(1);
        JournalEntry entry = entries.get(0);
        assertThat(entry.outcome()).isEqualTo(Outcome.HANDLED);
        assertThat(entry.waitState()).isEqualTo(waitState);
        assertThat(entry.simulationId()).isPresent();
        assertThat(entry.variablesAtWaitState()).containsEntry("amount", 4200);
        assertThat(adapter.executedActions()).hasSize(1);
        assertThat(adapter.executedActions().get(0).waitState()).isEqualTo(waitState);
    }

    @Test
    void claimLostIsJournaledAsClaimLostNotAnError() {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState);
        adapter.simulateClaimLost(waitState.id());
        registry.add(externalTask(TOPIC).willComplete());

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();

        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
        JournalEntry entry = journal.entries().get(0);
        assertThat(entry.outcome()).isEqualTo(Outcome.CLAIM_LOST);
        assertThat(entry.error()).isEmpty();
        assertThat(adapter.executedActions()).isEmpty();
    }

    @Test
    void actionFailureIsJournaledAsActionFailedAndNeverRetried() throws InterruptedException {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState);
        adapter.simulateActionFailure(waitState.id());
        registry.add(externalTask(TOPIC).willComplete());

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();

        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);

        // Let a few more iterations run, to prove there is no retry.
        long iterationsSoFar = loop.iterationCount();
        awaitUntil(loop, () -> loop.iterationCount() >= iterationsSoFar + 3, AWAIT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
        JournalEntry entry = journal.entries().get(0);
        assertThat(entry.outcome()).isEqualTo(Outcome.ACTION_FAILED);
        assertThat(entry.error()).isPresent();
        assertThat(adapter.executedActions()).isEmpty();
    }

    @Test
    void notDeliveredClaimFailureIsRetriedAndLaterSucceeds() {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState);
        adapter.simulateNotDeliveredClaimFailureOnce(waitState.id());
        registry.add(externalTask(TOPIC).willComplete());

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();

        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
        assertThat(journal.entries().get(0).outcome()).isEqualTo(Outcome.HANDLED);
        assertThat(adapter.executedActions()).hasSize(1);
    }

    @Test
    void ambiguousClaimFailureIsJournaledAsActionFailedAndNeverRetried() throws InterruptedException {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState);
        adapter.simulateAmbiguousClaimFailure(waitState.id());
        registry.add(externalTask(TOPIC).willComplete());

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();

        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);

        // Let a few more iterations run, to prove there is no retry.
        long iterationsSoFar = loop.iterationCount();
        awaitUntil(loop, () -> loop.iterationCount() >= iterationsSoFar + 3, AWAIT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
        JournalEntry entry = journal.entries().get(0);
        assertThat(entry.outcome()).isEqualTo(Outcome.ACTION_FAILED);
        assertThat(entry.error()).isPresent();
        assertThat(entry.error().get()).startsWith("outcome unknown: ");
        assertThat(adapter.executedActions()).isEmpty();
    }

    @Test
    void notDeliveredExecuteFailureIsRetriedAndLaterSucceeds() {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState);
        adapter.simulateNotDeliveredExecuteFailureOnce(waitState.id());
        registry.add(externalTask(TOPIC).willComplete());

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();

        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
        assertThat(journal.entries().get(0).outcome()).isEqualTo(Outcome.HANDLED);
        assertThat(adapter.executedActions()).hasSize(1);
    }

    @Test
    void ambiguousExecuteFailureIsJournaledAsActionFailedAndNeverRetried() throws InterruptedException {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState);
        adapter.simulateAmbiguousExecuteFailure(waitState.id());
        registry.add(externalTask(TOPIC).willComplete());

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();

        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);

        // Let a few more iterations run, to prove there is no retry.
        long iterationsSoFar = loop.iterationCount();
        awaitUntil(loop, () -> loop.iterationCount() >= iterationsSoFar + 3, AWAIT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
        JournalEntry entry = journal.entries().get(0);
        assertThat(entry.outcome()).isEqualTo(Outcome.ACTION_FAILED);
        assertThat(entry.error()).isPresent();
        assertThat(entry.error().get()).startsWith("outcome unknown: ");
        assertThat(adapter.executedActions()).isEmpty();
    }

    @Test
    void engineWaitStateGoneOnExecuteIsJournaledAsClaimLostNotAnActionError() throws InterruptedException {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState);
        adapter.simulateWaitStateGoneOnExecute(waitState.id());
        registry.add(externalTask(TOPIC).willComplete());

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();

        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);

        // Let a few more iterations run, to prove there is no retry.
        long iterationsSoFar = loop.iterationCount();
        awaitUntil(loop, () -> loop.iterationCount() >= iterationsSoFar + 3, AWAIT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
        JournalEntry entry = journal.entries().get(0);
        assertThat(entry.outcome()).isEqualTo(Outcome.CLAIM_LOST);
        assertThat(entry.error()).isEmpty();
        assertThat(journal.entries()).noneMatch(e -> e.outcome() == Outcome.ACTION_FAILED);
        assertThat(adapter.executedActions()).isEmpty();
    }

    @Test
    void stopCalledFromTheLoopThreadItselfReturnsPromptlyWithoutSelfJoining() throws InterruptedException {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState);
        registry.add(externalTask(TOPIC).willComplete());

        NudgeLoop selfStoppingLoop = new NudgeLoop(
                new StopOnFirstExecuteAdapter(adapter),
                registry,
                journal,
                new ReentrantLock(),
                SHORT_POLL,
                false,
                50);
        loop = selfStoppingLoop;

        Instant beforeStart = Instant.now();
        selfStoppingLoop.start();

        awaitUntil(selfStoppingLoop, () -> !selfStoppingLoop.isRunning(), Duration.ofSeconds(10));

        // The self-join guard is what this bound actually tests: without it, thread.join()
        // called by a thread on itself blocks for the full 2s join timeout below, since a
        // thread can never observe itself as terminated while still running. One second is
        // a generous margin above the sub-100ms this normally takes, while comfortably
        // ruling out that 2s join.
        assertThat(Duration.between(beforeStart, Instant.now())).isLessThan(Duration.ofSeconds(1));
        assertThat(journal.entries()).hasSize(1);
        assertThat(journal.entries().get(0).outcome()).isEqualTo(Outcome.HANDLED);
    }

    /**
     * Delegates every call to a wrapped {@link FakeEngineAdapter}, except that the first
     * {@link #execute(WaitState, Action)} call also calls {@code stop(...)} on the
     * {@link NudgeLoop} that is driving it, from inside that very call &mdash; simulating an
     * adapter callback that stops the loop synchronously, to exercise the self-join guard
     * in {@link NudgeLoop#stop(Duration)}.
     */
    private final class StopOnFirstExecuteAdapter implements EngineAdapter {

        private final FakeEngineAdapter delegate;

        StopOnFirstExecuteAdapter(FakeEngineAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkConnectivity() {
            delegate.checkConnectivity();
        }

        @Override
        public List<WaitState> discover(DiscoveryQuery query) {
            return delegate.discover(query);
        }

        @Override
        public ClaimResult claim(WaitState waitState) {
            return delegate.claim(waitState);
        }

        @Override
        public Map<String, Object> variables(WaitState waitState) {
            return delegate.variables(waitState);
        }

        @Override
        public void execute(WaitState waitState, Action action) {
            delegate.execute(waitState, action);
            loop.stop(Duration.ofSeconds(2));
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Test
    void survivesADiscoveryExceptionAndContinuesOnTheNextIteration() {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.simulateDiscoveryFailure(waitState.kind());
        registry.add(externalTask(TOPIC).willComplete());

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();

        // Let several failing iterations happen without the loop thread dying.
        awaitUntil(loop, () -> loop.iterationCount() >= 5, AWAIT_TIMEOUT);
        assertThat(loop.isRunning()).isTrue();
        assertThat(journal.entries()).isEmpty();
    }

    @Test
    void unmatchedWaitStateIsJournaledOnceAndLeftUntouched() {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState, Map.of("amount", 100));
        registry.add(externalTask(TOPIC).withVariable("amount", 999).willComplete());

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();

        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);
        long iterationsSoFar = loop.iterationCount();
        awaitUntil(loop, () -> loop.iterationCount() >= iterationsSoFar + 3, AWAIT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
        assertThat(journal.entries().get(0).outcome()).isEqualTo(Outcome.UNMATCHED);
        assertThat(adapter.executedActions()).isEmpty();
    }

    @Test
    void precedenceIsLowestPriorityFirstThenNewestRegistration() {
        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState);

        Simulation lowPriorityFirst = externalTask(TOPIC).willComplete(withVariables(Map.of("winner", "low-priority")))
                .atPriority(1);
        Simulation defaultPriorityOlder = externalTask(TOPIC)
                .willComplete(withVariables(Map.of("winner", "older-default")));
        Simulation defaultPriorityNewer = externalTask(TOPIC)
                .willComplete(withVariables(Map.of("winner", "newer-default")));
        registry.add(defaultPriorityOlder);
        registry.add(defaultPriorityNewer);
        registry.add(lowPriorityFirst);

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();

        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
        assertThat(journal.entries().get(0).simulationId()).contains(lowPriorityFirst.id());

        loop.stop(Duration.ofSeconds(2));

        // Now remove the priority winner and check "newest wins among equal priority".
        journal.reset();
        registry.clear();
        registry.add(defaultPriorityOlder);
        registry.add(defaultPriorityNewer);
        NudgeLoop secondLoop = newLoop(SHORT_POLL, false);
        secondLoop.start();
        awaitUntil(secondLoop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);
        assertThat(journal.entries().get(0).simulationId()).contains(defaultPriorityNewer.id());
    }

    @Test
    void variablesAreFetchedOnlyWhenTheMatchingSelectorNeedsThemOrCaptureVariablesIsOn() {
        WaitState noCapture = LoopWaitStates.externalTask("task-no-capture", TOPIC);
        adapter.addWaitState(noCapture, Map.of("amount", 100));
        registry.add(externalTask(TOPIC).willComplete());

        NudgeLoop loop = newLoop(SHORT_POLL, false);
        loop.start();
        awaitUntil(loop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);
        assertThat(journal.entries().get(0).variablesAtWaitState()).isEmpty();
        loop.stop(Duration.ofSeconds(2));

        journal.reset();
        registry.clear();
        registry.add(externalTask(TOPIC).willComplete());
        NudgeLoop captureLoop = newLoop(SHORT_POLL, true);
        captureLoop.start();
        awaitUntil(captureLoop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);
        assertThat(journal.entries().get(0).variablesAtWaitState()).containsEntry("amount", 100);
        captureLoop.stop(Duration.ofSeconds(2));

        journal.reset();
        registry.clear();
        registry.add(externalTask(TOPIC).withVariable("amount", 100).willComplete());
        NudgeLoop requiresVariablesLoop = newLoop(SHORT_POLL, false);
        requiresVariablesLoop.start();
        awaitUntil(requiresVariablesLoop, () -> !journal.entries().isEmpty(), AWAIT_TIMEOUT);
        assertThat(journal.entries().get(0).variablesAtWaitState()).containsEntry("amount", 100);
    }

    @Test
    void aRuleAddedWhileTheLoopIsIdleIsAppliedWithoutWaitingTheFullPollInterval() {
        // No rules at all initially: the loop's first iteration returns immediately and then
        // parks in its poll wait for a very long interval.
        NudgeLoop loop = newLoop(Duration.ofSeconds(30), false);
        loop.start();
        loop.awaitIterationAfter(0, System.nanoTime() + Duration.ofSeconds(5).toNanos());
        assertThat(loop.iterationCount()).isGreaterThanOrEqualTo(1);

        WaitState waitState = LoopWaitStates.externalTask("task-1", TOPIC);
        adapter.addWaitState(waitState);
        registry.add(externalTask(TOPIC).willComplete());
        loop.wake();

        // Well under the 30s poll interval: proves the wake-up, not the natural poll, drove this.
        awaitUntil(loop, () -> !journal.entries().isEmpty(), Duration.ofSeconds(5));
        assertThat(journal.entries().get(0).outcome()).isEqualTo(Outcome.HANDLED);
    }

    @Test
    void freshIterationBaselineReturnsEmptyWhenIterationLockCannotBeAcquiredWithinTheTimeout() throws Exception {
        // ReentrantLock is reentrant, so the lock must be held by a different thread than
        // the one calling freshIterationBaseline() for tryLock() to genuinely time out.
        ReentrantLock externallyHeldLock = new ReentrantLock();
        NudgeLoop loopWithHeldLock =
                new NudgeLoop(adapter, registry, journal, externallyHeldLock, SHORT_POLL, false, 50);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            externallyHeldLock.lock();
            try {
                lockAcquired.countDown();
                releaseLock.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                externallyHeldLock.unlock();
            }
        }, "lock-holder");
        holder.setDaemon(true);
        holder.start();
        try {
            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            OptionalLong baseline = loopWithHeldLock.freshIterationBaseline(Duration.ofMillis(50));
            assertThat(baseline)
                    .as("no fresh baseline can be honestly reported while iterationLock is held elsewhere")
                    .isEmpty();
        } finally {
            releaseLock.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void freshIterationBaselineReturnsTheCurrentCountOnceIterationLockIsAvailable() {
        NudgeLoop loop = newLoop(SHORT_POLL, false);
        OptionalLong baseline = loop.freshIterationBaseline(Duration.ofSeconds(1));
        assertThat(baseline).hasValue(loop.iterationCount());
    }
}
