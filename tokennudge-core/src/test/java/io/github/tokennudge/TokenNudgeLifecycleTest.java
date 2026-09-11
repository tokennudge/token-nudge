package io.github.tokennudge;

import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.spi.EngineAccessException;
import io.github.tokennudge.spi.EngineAdapterProvider;
import io.github.tokennudge.spi.EngineConfig;
import io.github.tokennudge.testsupport.FakeEngineAdapter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.github.tokennudge.TokenNudge.externalTask;
import static io.github.tokennudge.TokenNudge.forAdapter;
import static io.github.tokennudge.TokenNudge.forEngine;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link TokenNudge}'s lifecycle, {@link NudgeOperations} implementation, and
 * {@link TokenNudge.Builder}, against {@link FakeEngineAdapter}.
 */
class TokenNudgeLifecycleTest {

    private static final String TOPIC = "charge-card";
    private static final Duration SHORT_POLL = Duration.ofMillis(20);
    private static final Duration SHORT_VERIFY_TIMEOUT = Duration.ofMillis(500);

    private static WaitState waitState(String id) {
        return new WaitState(WaitStateKind.EXTERNAL_TASK, id, TOPIC, "pi-" + id, "payment", "act", null, "ex-" + id, null);
    }

    private TokenNudge newNudge(FakeEngineAdapter adapter) {
        return forAdapter(adapter)
                .pollInterval(SHORT_POLL)
                .verifyTimeout(SHORT_VERIFY_TIMEOUT)
                .build();
    }

    // --- Lifecycle ---------------------------------------------------------------------

    @Test
    void startAndStopAreIdempotentAndTrackRunningState() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        TokenNudge nudge = newNudge(adapter);

        assertThat(nudge.isRunning()).isFalse();
        nudge.start();
        assertThat(nudge.isRunning()).isTrue();
        nudge.start();
        assertThat(nudge.isRunning()).isTrue();

        nudge.stop();
        assertThat(nudge.isRunning()).isFalse();
        nudge.stop();
        assertThat(nudge.isRunning()).isFalse();
    }

    @Test
    void checkConnectivityFailureOnStartPropagatesAndLeavesTheInstanceNotRunning() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        adapter.simulateConnectivityFailure(true);
        TokenNudge nudge = newNudge(adapter);

        assertThatThrownBy(nudge::start).isInstanceOf(EngineAccessException.class);
        assertThat(nudge.isRunning()).isFalse();
    }

    @Test
    void stopClosesTheAdapterAndCloseIsEquivalentToStop() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        TokenNudge nudge = newNudge(adapter).start();

        assertThat(adapter.isClosed()).isFalse();
        nudge.stop();
        assertThat(adapter.isClosed()).isTrue();

        FakeEngineAdapter secondAdapter = new FakeEngineAdapter();
        TokenNudge secondNudge = newNudge(secondAdapter).start();
        secondNudge.close();
        assertThat(secondAdapter.isClosed()).isTrue();
        assertThat(secondNudge.isRunning()).isFalse();
    }

    @Test
    void stopBeforeStartStillClosesTheAdapterExactlyOnce() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        TokenNudge nudge = newNudge(adapter);

        nudge.stop();
        assertThat(adapter.isClosed()).isTrue();
        nudge.stop();
        assertThat(adapter.isClosed()).isTrue();
    }

    // --- Reset / resetJournal / removeSimulation ----------------------------------------

    @Test
    void resetClearsSimulationsJournalAndHandledIdsButKeepsTheLoopRunning() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        adapter.addWaitState(waitState("task-1"));
        TokenNudge nudge = newNudge(adapter).start();

        nudge.simulate(externalTask(TOPIC).willComplete());
        nudge.verify(externalTask(TOPIC).completed().times(1));

        nudge.reset();
        assertThat(nudge.simulations()).isEmpty();
        assertThat(nudge.journal()).isEmpty();
        assertThat(nudge.isRunning()).isTrue();

        // Same underlying wait state id: only handled again if HandledWaitStates was cleared too.
        nudge.simulate(externalTask(TOPIC).willComplete());
        nudge.verify(externalTask(TOPIC).completed().times(1));

        nudge.stop();
    }

    @Test
    void resetJournalClearsOnlyTheJournalLeavingSimulationsAndHandledIdsInPlace() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        adapter.addWaitState(waitState("task-1"));
        TokenNudge nudge = newNudge(adapter).start();

        SimulationId id = nudge.simulate(externalTask(TOPIC).willComplete());
        nudge.verify(externalTask(TOPIC).completed().times(1));

        nudge.resetJournal();
        assertThat(nudge.journal()).isEmpty();
        assertThat(nudge.simulations()).extracting(Simulation::id).containsExactly(id);

        // The wait state id was already handled, and resetJournal() does not forget that.
        nudge.verify(externalTask(TOPIC).completed().never());

        nudge.stop();
    }

    @Test
    void removeSimulationRemovesAKnownRuleAndReturnsFalseForAnUnknownOne() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        TokenNudge nudge = newNudge(adapter);

        SimulationId id = nudge.simulate(externalTask(TOPIC).willComplete());
        assertThat(nudge.simulations()).hasSize(1);

        assertThat(nudge.removeSimulation(id)).isTrue();
        assertThat(nudge.simulations()).isEmpty();
        assertThat(nudge.removeSimulation(id)).isFalse();
    }

    // --- Verify --------------------------------------------------------------------------

    @Test
    void verifyPassesOnceTheLoopHandlesTheMatchingWaitState() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        adapter.addWaitState(waitState("task-1"), Map.of("amount", 4200));
        TokenNudge nudge = newNudge(adapter).start();

        nudge.simulate(externalTask(TOPIC).willComplete(withVariables(Map.of("paid", true))));

        nudge.verify(externalTask(TOPIC).completed().times(1).withVariable("amount", 4200));

        nudge.stop();
    }

    @Test
    void verifyFailsWithAClearMessageWhenNeverSatisfied() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        TokenNudge nudge = newNudge(adapter).start();

        assertThatThrownBy(() -> nudge.verify(externalTask(TOPIC).completed().within(Duration.ofMillis(100))))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("completed")
                .hasMessageContaining("at least 1")
                .hasMessageContaining("0 time(s)");

        nudge.stop();
    }

    @Test
    void journalActionErrorsAndUnmatchedReflectTheOutcomesRecorded() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        adapter.addWaitState(waitState("handled"));
        adapter.addWaitState(waitState("failed"));
        adapter.simulateActionFailure("failed");
        TokenNudge nudge = newNudge(adapter).start();

        nudge.simulate(externalTask(TOPIC).willComplete());
        nudge.verify(externalTask(TOPIC).reached().times(2).within(Duration.ofSeconds(2)));

        List<JournalEntry> journal = nudge.journal();
        List<JournalEntry> actionErrors = nudge.actionErrors();
        List<JournalEntry> unmatched = nudge.unmatched();

        assertThat(journal).hasSize(2);
        assertThat(actionErrors).hasSize(1);
        assertThat(actionErrors.get(0).waitState().id()).isEqualTo("failed");
        assertThat(unmatched).isEmpty();

        nudge.stop();
    }

    // --- Builder validation and defaults --------------------------------------------------

    @Test
    void forEngineAndForAdapterRejectNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> forEngine(null));
        assertThatNullPointerException().isThrownBy(() -> forAdapter(null));
    }

    @Test
    void buildViaForEngineWithoutAnAdapterProviderFailsClearlyWhenNoneIsOnTheClasspath() {
        // tokennudge-core has no ServiceLoader-registered EngineAdapterProvider on its own
        // test classpath, so resolution fails fast with a clear, actionable message.
        assertThatThrownBy(() -> TokenNudge.forEngine("http://localhost:8080/engine-rest").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tokennudge-camunda7");
    }

    @Test
    void builderRejectsInvalidDurationsAndCounts() {
        TokenNudge.Builder builder = forAdapter(new FakeEngineAdapter());
        assertThatNullPointerException().isThrownBy(() -> builder.pollInterval(null));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.pollInterval(Duration.ofMillis(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.verifyTimeout(Duration.ofMillis(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.requestTimeout(Duration.ofMillis(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.lockDuration(Duration.ofMillis(-1)));
        assertThatNullPointerException().isThrownBy(() -> builder.workerId(null));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.maxResultsPerPoll(0));
        assertThatNullPointerException().isThrownBy(() -> builder.basicAuth(null, "pw"));
        assertThatNullPointerException().isThrownBy(() -> builder.basicAuth("user", null));
        assertThatNullPointerException().isThrownBy(() -> builder.header(null, "v"));
        assertThatNullPointerException().isThrownBy(() -> builder.header("n", null));
        assertThatNullPointerException().isThrownBy(() -> builder.adapterProvider(null));
    }

    @Test
    void adapterProviderBypassesServiceLoaderAndReceivesTheBuilderConfiguredEngineConfig() {
        FakeEngineAdapter fake = new FakeEngineAdapter();
        EngineConfig[] received = new EngineConfig[1];
        EngineAdapterProvider provider = config -> {
            received[0] = config;
            return fake;
        };

        TokenNudge nudge = forEngine("http://localhost:8080/engine-rest")
                .requestTimeout(Duration.ofSeconds(3))
                .lockDuration(Duration.ofSeconds(7))
                .workerId("worker-x")
                .maxResultsPerPoll(11)
                .adapterProvider(provider)
                .build();

        assertThat(received[0].baseUri().toString()).isEqualTo("http://localhost:8080/engine-rest");
        assertThat(received[0].requestTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(received[0].lockDuration()).isEqualTo(Duration.ofSeconds(7));
        assertThat(received[0].workerId()).isEqualTo("worker-x");
        assertThat(received[0].maxResultsPerPoll()).isEqualTo(11);

        nudge.stop();
    }

    @Test
    void defaultEngineConfigMatchesThePlanDefaults() {
        EngineConfig[] received = new EngineConfig[1];
        EngineAdapterProvider provider = config -> {
            received[0] = config;
            return new FakeEngineAdapter();
        };

        TokenNudge nudge = forEngine("http://localhost:8080/engine-rest").adapterProvider(provider).build();

        assertThat(received[0].requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(received[0].lockDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(received[0].workerId()).startsWith("tokennudge-");
        assertThat(received[0].maxResultsPerPoll()).isEqualTo(50);
        assertThat(received[0].headers()).isEmpty();

        nudge.stop();
    }

    @Test
    void basicAuthBecomesAnAuthorizationHeader() {
        EngineConfig[] received = new EngineConfig[1];
        EngineAdapterProvider provider = config -> {
            received[0] = config;
            return new FakeEngineAdapter();
        };

        TokenNudge nudge = forEngine("http://localhost:8080/engine-rest")
                .basicAuth("alice", "s3cret")
                .adapterProvider(provider)
                .build();

        assertThat(received[0].headers()).containsKey("Authorization");
        assertThat(received[0].headers().get("Authorization")).startsWith("Basic ");

        nudge.stop();
    }

    @Test
    void captureVariablesDefaultsToTrue() {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        adapter.addWaitState(waitState("task-1"), Map.of("amount", 4200));
        // No captureVariables(...) call: relies on the documented default of true.
        TokenNudge nudge = forAdapter(adapter).pollInterval(SHORT_POLL).verifyTimeout(SHORT_VERIFY_TIMEOUT).build().start();

        nudge.simulate(externalTask(TOPIC).willComplete());
        nudge.verify(externalTask(TOPIC).completed().times(1));

        assertThat(nudge.journal().get(0).variablesAtWaitState()).containsEntry("amount", 4200);

        nudge.stop();
    }
}
