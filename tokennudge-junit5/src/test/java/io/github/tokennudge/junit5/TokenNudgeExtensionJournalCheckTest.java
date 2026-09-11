package io.github.tokennudge.junit5;

import io.github.tokennudge.TokenNudge;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

/**
 * {@code afterEach}'s journal check: {@link TokenNudgeExtension#failOnActionErrors(boolean)}
 * (default {@code true}) and {@link TokenNudgeExtension#failOnUnmatched(boolean)} (default
 * {@code false}), exercised via {@link EngineTestKit} against plain static fixture classes
 * (see {@link TokenNudgeExtensionStaticModeTest} for why they are not {@code @Nested}/{@code
 * *Test}-named).
 */
class TokenNudgeExtensionJournalCheckTest {

    @Test
    void actionErrorsFailTheTestWithAReadableMessageByDefault() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ActionErrorFailsByDefault.class))
                .execute();

        results.testEvents().assertStatistics(stats -> stats.finished(1).failed(1));
        results.testEvents().assertThatEvents().haveExactly(1, event(test(), finishedWithFailure(
                instanceOf(AssertionError.class),
                message(m -> m.contains("action error") && m.contains("boom-topic") && m.contains("task-1")))));
    }

    static class ActionErrorFailsByDefault {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void causesAnActionError() {
            WaitState waitState = externalTask("boom-topic", "task-1", "def-1");
            ADAPTER.delegate().addWaitState(waitState);
            ADAPTER.delegate().simulateActionFailure(waitState.id());
            nudge.simulate(TokenNudge.externalTask("boom-topic").willComplete());
            nudge.verify(TokenNudge.externalTask("boom-topic").completed().never());
        }
    }

    @Test
    void failOnActionErrorsFalseLetsTheTestPassDespiteAnActionError() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ActionErrorsIgnoredWhenDisabled.class))
                .execute();

        results.testEvents().assertStatistics(stats -> stats.finished(1).failed(0));
    }

    static class ActionErrorsIgnoredWhenDisabled {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .failOnActionErrors(false)
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void anActionErrorIsStillRecordedButDoesNotFailTheTest() {
            WaitState waitState = externalTask("boom-topic", "task-1", "def-1");
            ADAPTER.delegate().addWaitState(waitState);
            ADAPTER.delegate().simulateActionFailure(waitState.id());
            nudge.simulate(TokenNudge.externalTask("boom-topic").willComplete());
            nudge.verify(TokenNudge.externalTask("boom-topic").completed().never());

            assertThat(nudge.actionErrors()).hasSize(1);
        }
    }

    @Test
    void unmatchedWaitStatesDoNotFailTheTestByDefault() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(UnmatchedDefaultPasses.class))
                .execute();

        results.testEvents().assertStatistics(stats -> stats.finished(1).failed(0));
    }

    static class UnmatchedDefaultPasses {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void anUnmatchedWaitStateIsStillRecordedButDoesNotFailTheTest() {
            WaitState waitState = externalTask("some-topic", "task-1", "other-process");
            ADAPTER.delegate().addWaitState(waitState);
            nudge.simulate(TokenNudge.externalTask("some-topic").inProcess("expected-process").willComplete());
            nudge.verify(TokenNudge.externalTask("some-topic").completed().never());

            assertThat(nudge.unmatched()).hasSize(1);
        }
    }

    @Test
    void failOnUnmatchedTrueFailsTheTestWithAReadableMessage() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(UnmatchedFailsWhenEnabled.class))
                .execute();

        results.testEvents().assertStatistics(stats -> stats.finished(1).failed(1));
        results.testEvents().assertThatEvents().haveExactly(1, event(test(), finishedWithFailure(
                instanceOf(AssertionError.class),
                message(m -> m.contains("unmatched") && m.contains("some-topic")))));
    }

    static class UnmatchedFailsWhenEnabled {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .failOnUnmatched(true)
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void anUnmatchedWaitStateFailsTheTestWhenEnabled() {
            WaitState waitState = externalTask("some-topic", "task-1", "other-process");
            ADAPTER.delegate().addWaitState(waitState);
            nudge.simulate(TokenNudge.externalTask("some-topic").inProcess("expected-process").willComplete());
            nudge.verify(TokenNudge.externalTask("some-topic").completed().never());
        }
    }

    private static WaitState externalTask(String topic, String id, String processDefinitionKey) {
        return new WaitState(
                WaitStateKind.EXTERNAL_TASK, id, topic, "pi-1", processDefinitionKey, "activity-1", null, "pi-1",
                null);
    }
}
