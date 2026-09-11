package io.github.tokennudge.junit5;

import io.github.tokennudge.TokenNudge;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.container;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

/**
 * {@link TokenNudgeExtension}'s {@code ParameterResolver} support: injecting the managed
 * {@link TokenNudge} into {@code @Test}/{@code @BeforeEach} method parameters, and failing
 * clearly if it is ever requested for a test class constructor parameter (which runs before
 * this extension's {@code beforeEach} has had a chance to start anything).
 */
class TokenNudgeExtensionParameterResolutionTest {

    @Test
    void tokenNudgeIsInjectableIntoTestAndBeforeEachMethodParameters() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(InjectsIntoMethodParameters.class))
                .execute();

        results.testEvents().assertStatistics(stats -> stats.finished(1).failed(0));
    }

    static class InjectsIntoMethodParameters {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @BeforeEach
        void setUp(TokenNudge injected) {
            assertThat(injected).isSameAs(nudge.get());
            assertThat(injected.isRunning()).isTrue();
        }

        @Test
        void injectedIntoTheTestMethodToo(TokenNudge injected) {
            assertThat(injected).isSameAs(nudge.get());
        }
    }

    @Test
    void injectingTokenNudgeIntoATestClassConstructorFailsClearly() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(InjectsIntoConstructor.class))
                .execute();

        // Constructor parameter resolution happens while instantiating the test class, as part
        // of preparing to run its one @Test method, so the failure is reported against that
        // test, not a separate container-level event.
        results.testEvents().assertStatistics(stats -> stats.finished(1).failed(1));
        results.testEvents().assertThatEvents().haveExactly(1, event(test(), finishedWithFailure(
                message(m -> m.contains("constructor")))));
    }

    static class InjectsIntoConstructor {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        InjectsIntoConstructor(TokenNudge injected) {
            // never reached: resolving this parameter must fail before the constructor runs.
        }

        @Test
        void neverRuns() {
        }
    }

    @Test
    void injectingTokenNudgeIntoABeforeAllMethodFailsClearlyTooNotOnlyForConstructors() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(InjectsIntoBeforeAllMethod.class))
                .execute();

        // beforeEach (which starts the instance) never runs before a static @BeforeAll method,
        // so get() throws "has not started yet", wrapped by resolveParameter as a
        // ParameterResolutionException; this fails the whole class's container, before any of
        // its tests.
        results.containerEvents().assertThatEvents().haveExactly(
                1, event(container(InjectsIntoBeforeAllMethod.class), finishedWithFailure()));
    }

    static class InjectsIntoBeforeAllMethod {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @BeforeAll
        static void classSetup(TokenNudge injected) {
            // never reached: resolving this parameter must fail before beforeEach starts anything.
        }

        @Test
        void neverRuns() {
        }
    }
}
