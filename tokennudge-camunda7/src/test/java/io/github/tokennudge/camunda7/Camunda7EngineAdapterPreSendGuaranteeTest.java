package io.github.tokennudge.camunda7;

import io.github.tokennudge.TokenNudge;
import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.spi.ClaimResult;
import io.github.tokennudge.spi.DiscoveryQuery;
import io.github.tokennudge.spi.EngineActionException;
import io.github.tokennudge.spi.EngineAdapter;
import io.github.tokennudge.spi.EngineConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.github.tokennudge.TokenNudge.externalTask;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the "pre-send" guarantee (iteration 6b review carry-over 2): an
 * unencodable variable value must fail as a definite {@link EngineActionException} rather
 * than an ambiguous "outcome unknown", both at the adapter level and end to end through
 * {@link TokenNudge}, even when the adapter is pointed at an unreachable engine. Since no
 * HTTP call must ever be attempted for this failure, a closed local port proves it: if the
 * adapter had tried to send anything, it would have failed with connection refused
 * ({@link io.github.tokennudge.spi.EngineAccessException}), not this exception.
 */
class Camunda7EngineAdapterPreSendGuaranteeTest {

    @Test
    void adapterInstanceRejectsAnUnencodableVariableBeforeAttemptingAnyHttpCall() throws IOException {
        Camunda7EngineAdapter adapter = new Camunda7EngineAdapter(closedPortConfig("worker-1"));
        WaitState waitState = waitState();

        assertThatThrownBy(() -> adapter.execute(
                        waitState, new CompleteExternalTask(withVariables(Map.of("amount", BigDecimal.TEN)))))
                .isInstanceOf(EngineActionException.class)
                .hasMessageContaining("CompleteExternalTask")
                .hasMessageNotContaining("outcome unknown")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loopNeverRetriesAPreSendEncodingFailureAndDoesNotJournalItAsOutcomeUnknown() throws IOException {
        DiscoveringAdapter adapter = new DiscoveringAdapter(closedPortConfig("worker-2"), waitState());
        TokenNudge nudge = TokenNudge.forAdapter(adapter)
                .pollInterval(Duration.ofMillis(20))
                .verifyTimeout(Duration.ofSeconds(5))
                .start();
        try {
            nudge.simulate(externalTask("it-charge").willComplete(withVariables(Map.of("amount", BigDecimal.TEN))));

            // Forces at least one full iteration; "completed()" never happens (the action fails).
            nudge.verify(externalTask("it-charge").completed().never());

            List<JournalEntry> actionErrors = nudge.actionErrors();
            assertThat(actionErrors).hasSize(1);
            JournalEntry entry = actionErrors.get(0);
            assertThat(entry.outcome()).isEqualTo(Outcome.ACTION_FAILED);
            assertThat(entry.error()).isPresent();
            assertThat(entry.error().orElseThrow()).doesNotStartWith("outcome unknown");

            // Never retried: force one more fresh iteration and confirm no second attempt.
            nudge.verify(externalTask("it-charge").completed().never());
            assertThat(nudge.actionErrors()).hasSize(1);
        } finally {
            nudge.stop();
        }
    }

    private static EngineConfig closedPortConfig(String workerId) throws IOException {
        int closedPort = findFreePortWithNoListener();
        return new EngineConfig(
                URI.create("http://127.0.0.1:" + closedPort), Duration.ofSeconds(1), Duration.ofSeconds(30),
                workerId, 50, Map.of());
    }

    private static int findFreePortWithNoListener() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static WaitState waitState() {
        return new WaitState(
                WaitStateKind.EXTERNAL_TASK, "task-1", "it-charge", "pi-1", "it-external-simple", "chargeTask",
                "order-1", "pi-1", null);
    }

    /**
     * Wraps a real {@link Camunda7EngineAdapter} (pointed at a closed port) so it can be
     * driven end to end through {@link TokenNudge} without a real engine: discovery, claim,
     * and connectivity are faked in-memory (the real adapter's own transport tests already
     * cover those), while {@link #execute} delegates to the real adapter, which is what
     * actually builds the request and must fail before any HTTP call.
     */
    private static final class DiscoveringAdapter implements EngineAdapter {

        private final Camunda7EngineAdapter delegate;
        private final WaitState discoverable;

        DiscoveringAdapter(EngineConfig config, WaitState discoverable) {
            this.delegate = new Camunda7EngineAdapter(config);
            this.discoverable = discoverable;
        }

        @Override
        public void checkConnectivity() {
            // No-op: this test never reaches the engine at all, by design.
        }

        @Override
        public List<WaitState> discover(DiscoveryQuery query) {
            return query.kind() == discoverable.kind() && query.names().contains(discoverable.name())
                    ? List.of(discoverable)
                    : List.of();
        }

        @Override
        public ClaimResult claim(WaitState waitState) {
            return ClaimResult.CLAIMED;
        }

        @Override
        public Map<String, Object> variables(WaitState waitState) {
            return Map.of();
        }

        @Override
        public void execute(WaitState waitState, Action action) {
            delegate.execute(waitState, action);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
