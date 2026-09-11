package io.github.tokennudge;

import io.github.tokennudge.internal.InMemoryJournal;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.FailExternalTask;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationEvaluatorTest {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(100);

    private static WaitState waitState(String id) {
        return new WaitState(
                WaitStateKind.EXTERNAL_TASK, id, "charge-card", "pi-1", "payment", "act", "order-42", "ex-1", null);
    }

    private static void recordHandled(InMemoryJournal journal, WaitState waitState) {
        journal.append(
                waitState,
                Optional.of(SimulationId.newId()),
                Optional.of(new CompleteExternalTask(Variables.empty())),
                Outcome.HANDLED,
                Map.of(),
                Optional.empty());
    }

    @Test
    void loopNotRunningEvaluatesImmediatelyAndSucceedsIfAlreadySatisfied() {
        InMemoryJournal journal = new InMemoryJournal();
        recordHandled(journal, waitState("task-1"));
        FakeIterationClock clock = new FakeIterationClock();
        clock.setRunning(false);

        Verification verification = TokenNudge.externalTask("charge-card").completed();

        new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT);
        // no exception: success
    }

    @Test
    void loopNotRunningEvaluatesImmediatelyAndFailsIfNotSatisfied() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock();
        clock.setRunning(false);

        Verification verification = TokenNudge.externalTask("charge-card").completed();

        assertThatThrownBy(() -> new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT))
                .isInstanceOf(VerificationException.class);
    }

    @Test
    void monotonicExpectationAlreadySatisfiedReturnsWithoutWaiting() {
        InMemoryJournal journal = new InMemoryJournal();
        recordHandled(journal, waitState("task-1"));
        FakeIterationClock clock = new FakeIterationClock();
        // No scripted iterations queued: if the evaluator waited, the fake would jump
        // straight to the deadline; since it must not wait, no exception should occur.

        Verification verification = TokenNudge.externalTask("charge-card").completed();

        new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT);
    }

    @Test
    void monotonicExpectationWaitsAcrossIterationsUntilSatisfied() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock();
        clock.queueIteration(() -> recordHandled(journal, waitState("task-1")));

        Verification verification = TokenNudge.externalTask("charge-card").completed();

        new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
    }

    @Test
    void monotonicExpectationTimesOutWhenNeverSatisfied() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock();
        // No scripted iterations: the fake jumps straight to the deadline.

        Verification verification = TokenNudge.externalTask("charge-card").completed();

        assertThatThrownBy(() -> new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("at least 1")
                .hasMessageContaining("0 time(s)");
    }

    @Test
    void timesFailsFastAsSoonAsCountExceedsExpectationWithoutWaitingForTimeout() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock();
        clock.queueIteration(() -> {
            recordHandled(journal, waitState("task-1"));
            recordHandled(journal, waitState("task-2"));
        });
        // A second scripted iteration that must never run, because fail-fast should trigger
        // right after the first one.
        clock.queueIteration(() -> recordHandled(journal, waitState("task-3")));

        Verification verification = TokenNudge.externalTask("charge-card").completed().times(1);

        assertThatThrownBy(() -> new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("exactly 1");
        assertThat(journal.entries()).hasSize(2);
    }

    @Test
    void nonMonotonicNeverSucceedsWhenNoMatchOccursDuringTheAwaitedIteration() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock();
        clock.queueIteration(() -> {
            // Something unrelated happens, but nothing matching this verification.
        });

        Verification verification = TokenNudge.externalTask("charge-card").completed().never();

        new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT);
    }

    @Test
    void nonMonotonicNeverFailsWhenAMatchOccursDuringTheAwaitedIteration() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock();
        clock.queueIteration(() -> recordHandled(journal, waitState("task-1")));

        Verification verification = TokenNudge.externalTask("charge-card").completed().never();

        assertThatThrownBy(() -> new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("never");
    }

    @Test
    void nonMonotonicAtMostWaitsExactlyOneIterationThenEvaluatesOnce() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock();
        clock.queueIteration(() -> recordHandled(journal, waitState("task-1")));
        // Must not be consulted: atMost only waits for a single iteration.
        clock.queueIteration(() -> recordHandled(journal, waitState("task-2")));

        Verification verification = TokenNudge.externalTask("charge-card").completed().atMost(1);

        new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT);

        assertThat(journal.entries()).hasSize(1);
    }

    @Test
    void explicitTimeoutOnVerificationOverridesTheDefault() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock();

        Verification verification =
                TokenNudge.externalTask("charge-card").completed().within(Duration.ofMillis(5));

        assertThatThrownBy(
                        () -> new VerificationEvaluator(journal, clock).evaluate(verification, Duration.ofHours(1)))
                .isInstanceOf(VerificationException.class);
    }

    @Test
    void failureMessageListsNearMissesForStructurallyMatchingButUnsatisfyingEntries() {
        InMemoryJournal journal = new InMemoryJournal();
        WaitState waitState = waitState("task-1");
        journal.append(
                waitState,
                Optional.of(SimulationId.newId()),
                Optional.of(new FailExternalTask("boom", 0, Duration.ZERO)),
                Outcome.HANDLED,
                Map.of(),
                Optional.empty());
        FakeIterationClock clock = new FakeIterationClock();

        Verification verification = TokenNudge.externalTask("charge-card").completed();

        assertThatThrownBy(() -> new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("Matching entries: none.")
                .hasMessageContaining("Near misses")
                .hasMessageContaining("task-1");
    }

    @Test
    void journalEntryTimestampsSurviveIntoTheEvaluatorUnchanged() {
        // Sanity check that the evaluator does not depend on wall-clock Instant timing.
        InMemoryJournal journal = new InMemoryJournal();
        recordHandled(journal, waitState("task-1"));
        assertThat(journal.entries().get(0).timestamp()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void monotonicDeadlineComputationDoesNotOverflowWhenClockIsNearMaxValue() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock(Long.MAX_VALUE - 5);
        // No scripted iterations: the fake would jump to the (saturated) deadline.

        Verification verification = TokenNudge.externalTask("charge-card").completed();

        assertThatThrownBy(() -> new VerificationEvaluator(journal, clock)
                        .evaluate(verification, Duration.ofNanos(100)))
                .isInstanceOf(VerificationException.class);
    }

    @Test
    void nonMonotonicVerificationFailsExplicitlyWhenNoFreshIterationBaselineIsAvailable() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock();
        clock.makeFreshIterationBaselineUnavailable();
        // Queue an iteration that would otherwise satisfy the verification if it were
        // (wrongly) evaluated against a stale baseline; it must never be consulted.
        clock.queueIteration(() -> recordHandled(journal, waitState("task-1")));

        Verification verification = TokenNudge.externalTask("charge-card").completed().never();

        assertThatThrownBy(() -> new VerificationEvaluator(journal, clock).evaluate(verification, DEFAULT_TIMEOUT))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("timed out waiting for a fresh loop iteration");
    }

    @Test
    void nonMonotonicDeadlineComputationDoesNotOverflowWhenClockIsNearMaxValue() {
        InMemoryJournal journal = new InMemoryJournal();
        FakeIterationClock clock = new FakeIterationClock(Long.MAX_VALUE - 5);
        clock.queueIteration(() -> {
            // Nothing matching happens during the awaited iteration.
        });

        Verification verification = TokenNudge.externalTask("charge-card").completed().never();

        new VerificationEvaluator(journal, clock).evaluate(verification, Duration.ofNanos(100));
    }
}
