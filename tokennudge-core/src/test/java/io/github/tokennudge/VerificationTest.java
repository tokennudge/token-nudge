package io.github.tokennudge;

import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.FailExternalTask;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.model.ThrowBpmnError;
import io.github.tokennudge.model.WaitState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class VerificationTest {

    @Test
    void defaultExpectationIsAtLeastOne() {
        Verification verification = TokenNudge.externalTask("charge-card").reached();
        assertThat(verification.isMonotonic()).isTrue();
        assertThat(verification.isSatisfiedBy(0)).isFalse();
        assertThat(verification.isSatisfiedBy(1)).isTrue();
        assertThat(verification.describeExpectation()).contains("at least 1");
    }

    @Test
    void timesIsMonotonicAndExactAndFailsFastWhenExceeded() {
        Verification verification = TokenNudge.externalTask("charge-card").reached().times(2);
        assertThat(verification.isMonotonic()).isTrue();
        assertThat(verification.isSatisfiedBy(1)).isFalse();
        assertThat(verification.isSatisfiedBy(2)).isTrue();
        assertThat(verification.isExceededBy(2)).isFalse();
        assertThat(verification.isExceededBy(3)).isTrue();
    }

    @Test
    void atLeastIsMonotonicAndNeverExceeded() {
        Verification verification = TokenNudge.externalTask("charge-card").reached().atLeast(2);
        assertThat(verification.isMonotonic()).isTrue();
        assertThat(verification.isSatisfiedBy(2)).isTrue();
        assertThat(verification.isSatisfiedBy(100)).isTrue();
        assertThat(verification.isExceededBy(100)).isFalse();
    }

    @Test
    void atMostIsNotMonotonic() {
        Verification verification = TokenNudge.externalTask("charge-card").reached().atMost(1);
        assertThat(verification.isMonotonic()).isFalse();
        assertThat(verification.isSatisfiedBy(0)).isTrue();
        assertThat(verification.isSatisfiedBy(1)).isTrue();
        assertThat(verification.isSatisfiedBy(2)).isFalse();
    }

    @Test
    void neverIsEquivalentToAtMostZero() {
        Verification verification = TokenNudge.externalTask("charge-card").reached().never();
        assertThat(verification.isMonotonic()).isFalse();
        assertThat(verification.isSatisfiedBy(0)).isTrue();
        assertThat(verification.isSatisfiedBy(1)).isFalse();
        assertThat(verification.describeExpectation()).contains("never");
    }

    @Test
    void negativeCountsAreRejected() {
        var spec = TokenNudge.externalTask("charge-card");
        assertThatIllegalArgumentException().isThrownBy(() -> spec.reached().times(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> spec.reached().atLeast(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> spec.reached().atMost(-1));
    }

    @Test
    void withinSetsExplicitTimeout() {
        Verification withoutTimeout = TokenNudge.externalTask("charge-card").reached();
        Verification withTimeout = withoutTimeout.within(Duration.ofSeconds(1));

        assertThat(withoutTimeout.explicitTimeout()).isNull();
        assertThat(withTimeout.explicitTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void withinRejectsNullOrNegativeDuration() {
        Verification verification = TokenNudge.externalTask("charge-card").reached();
        assertThatNullPointerException().isThrownBy(() -> verification.within(null));
        assertThatIllegalArgumentException().isThrownBy(() -> verification.within(Duration.ofSeconds(-1)));
    }

    @Test
    void reachedMatchesAnyOutcomeForTheSelectedWaitState() {
        Verification verification = TokenNudge.externalTask("charge-card").reached();
        WaitState waitState = JournalEntries.externalTask("task-1", "charge-card");

        JournalEntry unmatched = JournalEntries.unmatched(waitState);
        JournalEntry handled = JournalEntries.entry(
                waitState, Outcome.HANDLED, new CompleteExternalTask(Variables.empty()), Map.of());

        assertThat(verification.matches(unmatched)).isTrue();
        assertThat(verification.matches(handled)).isTrue();
    }

    @Test
    void reachedDoesNotMatchADifferentTopic() {
        Verification verification = TokenNudge.externalTask("charge-card").reached();
        WaitState otherTopic = JournalEntries.externalTask("task-1", "risk-check");

        assertThat(verification.matches(JournalEntries.unmatched(otherTopic))).isFalse();
        assertThat(verification.matchesWaitState(JournalEntries.unmatched(otherTopic))).isFalse();
    }

    @Test
    void handledRequiresHandledOutcome() {
        Verification verification = TokenNudge.externalTask("charge-card").handled();
        WaitState waitState = JournalEntries.externalTask("task-1", "charge-card");

        JournalEntry unmatched = JournalEntries.unmatched(waitState);
        JournalEntry handled = JournalEntries.entry(
                waitState, Outcome.HANDLED, new CompleteExternalTask(Variables.empty()), Map.of());

        assertThat(verification.matchesWaitState(unmatched)).isTrue();
        assertThat(verification.matches(unmatched)).isFalse();
        assertThat(verification.matches(handled)).isTrue();
    }

    @Test
    void completedRequiresCompleteExternalTaskAction() {
        Verification verification = TokenNudge.externalTask("charge-card").completed();
        WaitState waitState = JournalEntries.externalTask("task-1", "charge-card");

        JournalEntry completed = JournalEntries.entry(
                waitState, Outcome.HANDLED, new CompleteExternalTask(Variables.empty()), Map.of());
        JournalEntry failed = JournalEntries.entry(
                waitState, Outcome.HANDLED, new FailExternalTask("boom", 0, Duration.ZERO), Map.of());

        assertThat(verification.matches(completed)).isTrue();
        assertThat(verification.matches(failed)).isFalse();
        assertThat(verification.matchesWaitState(failed)).isTrue();
    }

    @Test
    void failedWithBpmnErrorWithoutCodeAcceptsAnyCode() {
        Verification verification = TokenNudge.externalTask("risk-check").failedWithBpmnError();
        WaitState waitState = JournalEntries.externalTask("task-1", "risk-check");

        JournalEntry entry = JournalEntries.entry(
                waitState, Outcome.HANDLED, new ThrowBpmnError("ANY_CODE", null, Variables.empty()), Map.of());

        assertThat(verification.matches(entry)).isTrue();
    }

    @Test
    void failedWithBpmnErrorWithCodeRequiresExactCode() {
        Verification verification = TokenNudge.externalTask("risk-check").failedWithBpmnError("RISK_REJECTED");
        WaitState waitState = JournalEntries.externalTask("task-1", "risk-check");

        JournalEntry matching = JournalEntries.entry(
                waitState, Outcome.HANDLED, new ThrowBpmnError("RISK_REJECTED", null, Variables.empty()), Map.of());
        JournalEntry otherCode = JournalEntries.entry(
                waitState, Outcome.HANDLED, new ThrowBpmnError("OTHER_CODE", null, Variables.empty()), Map.of());

        assertThat(verification.matches(matching)).isTrue();
        assertThat(verification.matches(otherCode)).isFalse();
        assertThat(verification.matchesWaitState(otherCode)).isTrue();
    }

    @Test
    void failedRequiresFailExternalTaskAction() {
        Verification verification = TokenNudge.externalTask("charge-card").failed();
        WaitState waitState = JournalEntries.externalTask("task-1", "charge-card");

        JournalEntry failed = JournalEntries.entry(
                waitState, Outcome.HANDLED, new FailExternalTask("boom", 0, Duration.ZERO), Map.of());
        JournalEntry completed = JournalEntries.entry(
                waitState, Outcome.HANDLED, new CompleteExternalTask(Variables.empty()), Map.of());

        assertThat(verification.matches(failed)).isTrue();
        assertThat(verification.matches(completed)).isFalse();
    }

    @Test
    void withVariableFiltersOnCapturedVariablesNotOnAnythingElse() {
        Verification verification =
                TokenNudge.externalTask("charge-card").completed().withVariable("amount", 4200);
        WaitState waitState = JournalEntries.externalTask("task-1", "charge-card");

        JournalEntry matchingAmount = JournalEntries.entry(
                waitState, Outcome.HANDLED, new CompleteExternalTask(Variables.empty()), Map.of("amount", 4200L));
        JournalEntry differentAmount = JournalEntries.entry(
                waitState, Outcome.HANDLED, new CompleteExternalTask(Variables.empty()), Map.of("amount", 1));

        assertThat(verification.matches(matchingAmount)).isTrue();
        assertThat(verification.matches(differentAmount)).isFalse();
        assertThat(verification.matchesWaitState(differentAmount)).isTrue();
    }

    @Test
    void withVariableRejectsNullName() {
        Verification verification = TokenNudge.externalTask("charge-card").completed();
        assertThatNullPointerException().isThrownBy(() -> verification.withVariable(null, 1));
    }

    @Test
    void specLevelWithVariableAffectsBothSelectionAndVerification() {
        Verification verification = TokenNudge.externalTask("charge-card").withVariable("amount", 4200).completed();
        WaitState waitState = JournalEntries.externalTask("task-1", "charge-card");

        JournalEntry matching = JournalEntries.entry(
                waitState, Outcome.HANDLED, new CompleteExternalTask(Variables.empty()), Map.of("amount", 4200));
        JournalEntry nonMatching = JournalEntries.entry(
                waitState, Outcome.HANDLED, new CompleteExternalTask(Variables.empty()), Map.of("amount", 1));

        assertThat(verification.matches(matching)).isTrue();
        assertThat(verification.matches(nonMatching)).isFalse();
        assertThat(verification.matchesWaitState(nonMatching)).isFalse();
    }

    @Test
    void matchesAndMatchesWaitStateRejectNullEntry() {
        Verification verification = TokenNudge.externalTask("charge-card").reached();
        assertThatNullPointerException().isThrownBy(() -> verification.matches(null));
        assertThatNullPointerException().isThrownBy(() -> verification.matchesWaitState(null));
    }
}
