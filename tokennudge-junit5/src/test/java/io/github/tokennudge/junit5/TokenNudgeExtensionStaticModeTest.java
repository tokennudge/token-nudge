package io.github.tokennudge.junit5;

import io.github.tokennudge.TokenNudge;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.suppressed;

/**
 * Static-field ({@code @RegisterExtension static}) lifecycle behavior of
 * {@link TokenNudgeExtension}, exercised via {@link EngineTestKit} against plain static
 * fixture classes (deliberately not {@code @Nested} and not named {@code *Test}, so normal
 * Surefire/Jupiter discovery never runs them directly; only the {@code @Test} methods below,
 * which select and execute them explicitly, do).
 */
class TokenNudgeExtensionStaticModeTest {

    @Test
    void getThrowsBeforeTheExtensionHasEverStarted() {
        TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest");

        assertThatThrownBy(nudge::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has not started yet");
    }

    @Test
    void theEngineRestUrlSupplierIsNotCalledBeforeTheFirstBeforeEach() {
        assertThat(LazySupplierStart.supplierCalled.get()).isFalse();

        EngineExecutionResults results =
                EngineTestKit.engine("junit-jupiter").selectors(selectClass(LazySupplierStart.class)).execute();

        results.testEvents().assertStatistics(stats -> stats.finished(1).failed(0));
        assertThat(LazySupplierStart.supplierCalled.get()).isTrue();
    }

    static class LazySupplierStart {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();
        static final AtomicBoolean supplierCalled = new AtomicBoolean(false);

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine(() -> {
            supplierCalled.set(true);
            return "http://example.invalid/engine-rest";
        }).configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void bySecondTestTheSupplierHasAlreadyBeenCalled() {
            assertThat(supplierCalled.get()).isTrue();
        }
    }

    @Test
    void simulationsAndTheJournalAreResetBetweenTests() {
        EngineExecutionResults results =
                EngineTestKit.engine("junit-jupiter").selectors(selectClass(ResetBetweenTests.class)).execute();

        results.testEvents().assertStatistics(stats -> stats.finished(2).failed(0));
    }

    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    static class ResetBetweenTests {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        @Order(1)
        void firstTestRegistersASimulation() {
            var id = nudge.simulate(TokenNudge.externalTask("topic-a").willComplete());
            assertThat(nudge.simulations()).hasSize(1);

            // Exercise the remaining NudgeOperations delegation methods directly.
            assertThat(nudge.removeSimulation(id)).isTrue();
            assertThat(nudge.simulations()).isEmpty();
            nudge.simulate(TokenNudge.externalTask("topic-a").willComplete());
            nudge.resetJournal();
            nudge.reset();
        }

        @Test
        @Order(2)
        void secondTestSeesAFreshRegistryAndJournal() {
            assertThat(nudge.simulations()).isEmpty();
            assertThat(nudge.journal()).isEmpty();
        }
    }

    @Test
    void staticModeStartsOnceAndStopsOnceInAfterAllClosingTheAdapter() {
        EngineExecutionResults results =
                EngineTestKit.engine("junit-jupiter").selectors(selectClass(StopsOnceInAfterAll.class)).execute();

        results.testEvents().assertStatistics(stats -> stats.finished(2).failed(0));
        assertThat(StopsOnceInAfterAll.ADAPTER.connectivityChecks()).isEqualTo(1);
        assertThat(StopsOnceInAfterAll.ADAPTER.closes()).isEqualTo(1);
        assertThat(StopsOnceInAfterAll.ADAPTER.delegate().isClosed()).isTrue();
    }

    static class StopsOnceInAfterAll {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .pollInterval(Duration.ofMillis(20))
                .verifyTimeout(Duration.ofSeconds(2))
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void firstTestSeesARunningInstance() {
            assertThat(nudge.get().isRunning()).isTrue();
        }

        @Test
        void secondTestStillSeesTheSameRunningInstance() {
            assertThat(nudge.get().isRunning()).isTrue();
        }
    }

    @Test
    void aNestedClassInheritsTheExtensionWithoutStoppingOrRebuildingTheOuterInstance() {
        EngineExecutionResults results =
                EngineTestKit.engine("junit-jupiter").selectors(selectClass(NestedInheritance.class)).execute();

        results.testEvents().assertStatistics(stats -> stats.finished(2).failed(0));
        assertThat(NestedInheritance.ADAPTER.connectivityChecks()).isEqualTo(1);
        assertThat(NestedInheritance.ADAPTER.closes()).isEqualTo(1);
    }

    static class NestedInheritance {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void outerTestSeesARunningInstance() {
            assertThat(nudge.get().isRunning()).isTrue();
        }

        @Nested
        class Inner {

            @Test
            void innerTestSeesTheSameRunningInstanceStillUnstopped() {
                assertThat(nudge.get().isRunning()).isTrue();
            }
        }
    }

    @Test
    void aStartFailureFailsEveryTestClearlyAndIsNeverRetried() {
        EngineExecutionResults results =
                EngineTestKit.engine("junit-jupiter").selectors(selectClass(StartFailsClearly.class)).execute();

        results.testEvents().assertStatistics(stats -> stats.finished(2).failed(2));
        results.testEvents().assertThatEvents().haveExactly(2, event(test(), finishedWithFailure(
                instanceOf(IllegalStateException.class),
                message(m -> m.contains("fake connectivity failure")))));
        // Cached, not retried: only the very first beforeEach actually attempted to connect.
        assertThat(StartFailsClearly.ADAPTER.connectivityChecks()).isEqualTo(1);
        // The owning class's afterAll releases the cached failure along with the instance, so a
        // later owning class of the same field starts fresh instead of inheriting this failure.
        assertThatThrownBy(StartFailsClearly.nudge::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has not started yet");
    }

    @Test
    void siblingClassesInheritingTheStaticFieldEachGetTheirOwnStartedInstance() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(SiblingA.class), selectClass(SiblingB.class))
                .execute();

        results.testEvents().assertStatistics(stats -> stats.finished(2).failed(0));
        // Each sibling owns the field in turn: started and stopped once per class, rather than the
        // second class silently reusing the first one's already stopped instance.
        assertThat(SharedExtensionBase.ADAPTER.connectivityChecks()).isEqualTo(2);
        assertThat(SharedExtensionBase.ADAPTER.closes()).isEqualTo(2);
    }

    abstract static class SharedExtensionBase {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));
    }

    static class SiblingA extends SharedExtensionBase {

        @Test
        void seesARunningInstance() {
            assertThat(nudge.get().isRunning()).isTrue();
        }
    }

    static class SiblingB extends SharedExtensionBase {

        @Test
        void alsoSeesARunningInstance() {
            assertThat(nudge.get().isRunning()).isTrue();
        }
    }

    static class StartFailsClearly {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        static {
            ADAPTER.delegate().simulateConnectivityFailure(true);
        }

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void firstTestFailsBecauseTheEngineIsUnreachable() {
        }

        @Test
        void secondTestAlsoFailsWithTheSameCachedFailure() {
        }
    }

    @Test
    void whenATestFailsTheActionErrorCheckFailureIsAddedAsSuppressedNotPrimary() {
        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(TestFailurePlusActionErrorSuppressed.class))
                .execute();

        results.testEvents().assertStatistics(stats -> stats.finished(1).failed(1));
        results.testEvents().assertThatEvents().haveExactly(1, event(test(), finishedWithFailure(
                instanceOf(AssertionError.class),
                message(m -> m.contains("boom")),
                suppressed(0, instanceOf(AssertionError.class)))));
    }

    static class TestFailurePlusActionErrorSuppressed {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void thisTestFailsAndAlsoLeavesAnActionError() {
            WaitState waitState = new WaitState(
                    WaitStateKind.EXTERNAL_TASK, "task-1", "boom-topic", "pi-1", "def-1", "activity-1", null, "pi-1",
                    null);
            ADAPTER.delegate().addWaitState(waitState);
            ADAPTER.delegate().simulateActionFailure(waitState.id());
            nudge.simulate(TokenNudge.externalTask("boom-topic").willComplete());
            nudge.verify(TokenNudge.externalTask("boom-topic").completed().never());

            fail("boom");
        }
    }

    @Test
    void configuringAfterStartThrowsIllegalStateException() {
        EngineExecutionResults results =
                EngineTestKit.engine("junit-jupiter").selectors(selectClass(ConfigAfterStartThrows.class)).execute();

        results.testEvents().assertStatistics(stats -> stats.finished(1).failed(0));
    }

    static class ConfigAfterStartThrows {
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void everyConfigSetterThrowsOnceStarted() {
            assertThatThrownBy(() -> nudge.pollInterval(Duration.ofMillis(1)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> nudge.verifyTimeout(Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> nudge.failOnActionErrors(false)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> nudge.failOnUnmatched(true)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> nudge.configure(b -> {
            })).isInstanceOf(IllegalStateException.class);
        }
    }
}
