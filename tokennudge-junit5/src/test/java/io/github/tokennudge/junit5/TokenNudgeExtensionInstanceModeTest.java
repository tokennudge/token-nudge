package io.github.tokennudge.junit5;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Instance-field ({@code @RegisterExtension}, non-{@code static}) lifecycle behavior of
 * {@link TokenNudgeExtension}: since {@code beforeAll}/{@code afterAll} are never invoked for a
 * non-static field (JUnit does not have a test instance, and therefore cannot read the field,
 * before entering a container), a fresh {@link io.github.tokennudge.TokenNudge} is built and
 * started in {@code beforeEach} and stopped again in {@code afterEach}, for every test.
 */
class TokenNudgeExtensionInstanceModeTest {

    @Test
    void instanceModeStartsAndStopsAFreshInstancePerTest() {
        EngineExecutionResults results =
                EngineTestKit.engine("junit-jupiter").selectors(selectClass(StartsAndStopsPerTest.class)).execute();

        results.testEvents().assertStatistics(stats -> stats.finished(2).failed(0));
        assertThat(StartsAndStopsPerTest.ADAPTER.connectivityChecks()).isEqualTo(2);
        assertThat(StartsAndStopsPerTest.ADAPTER.closes()).isEqualTo(2);
    }

    static class StartsAndStopsPerTest {

        // Shared across both test instances/methods (a new TokenNudgeExtension field value is
        // created per test method with instance-field registration), so the adapter itself
        // must be a class-level singleton to observe start/stop counts across the whole run.
        static final CountingEngineAdapter ADAPTER = new CountingEngineAdapter();

        @RegisterExtension
        TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://example.invalid/engine-rest")
                .configure(b -> b.adapterProvider(config -> ADAPTER));

        @Test
        void firstTestSeesARunningInstance() {
            assertThat(nudge.get().isRunning()).isTrue();
        }

        @Test
        void secondTestAlsoSeesAFreshRunningInstance() {
            assertThat(nudge.get().isRunning()).isTrue();
        }
    }
}
