package io.github.tokennudge.camunda7;

import io.github.tokennudge.TokenNudge;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.tokennudge.TokenNudge.businessKey;
import static io.github.tokennudge.TokenNudge.message;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for {@link Camunda7EngineAdapter}'s message-correlation path
 * (discovery via {@code GET /event-subscription}, enrichment via
 * {@link ProcessInstanceResolver}, correlation via {@code POST /message}), driven entirely
 * through the public {@code io.github.tokennudge.TokenNudge} API. Covers PLAN.md §5 item 10's
 * "done" criteria.
 */
class Camunda7MessageCorrelationIT {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration AWAIT_ENDED_TIMEOUT = Duration.ofSeconds(10);

    private static EngineRestTestClient testClient;

    @BeforeAll
    static void deployFixtures() {
        String baseUrl = EngineContainer.sharedInstance().baseUrl();
        testClient = new EngineRestTestClient(baseUrl);
        testClient.deploy(
                "bpmn/it-message-catch.bpmn",
                "bpmn/it-receive-task.bpmn",
                "bpmn/it-message-start.bpmn");
    }

    private TokenNudge nudge;

    @BeforeEach
    void startNudge() {
        nudge = newBuilder().start();
    }

    @AfterEach
    void stopNudgeAndCleanUp() {
        if (nudge.isRunning()) {
            nudge.stop();
        }
        testClient.deleteAllProcessInstances();
    }

    @AfterAll
    static void tearDown() {
        testClient.deleteAllProcessInstances();
    }

    private static TokenNudge.Builder newBuilder() {
        return TokenNudge.forEngine(EngineContainer.sharedInstance().baseUrl())
                .pollInterval(POLL_INTERVAL)
                .verifyTimeout(VERIFY_TIMEOUT)
                .workerId("it-worker-" + UUID.randomUUID());
    }

    @Test
    void willCorrelateDefaultsToByProcessInstanceForAnIntermediateCatchEvent() {
        nudge.simulate(message("ItConfirmed").willCorrelate());

        String processInstanceId = testClient.startProcess("it-message-catch", "catch-order", Map.of());
        testClient.awaitEnded(processInstanceId, AWAIT_ENDED_TIMEOUT);

        nudge.verify(message("ItConfirmed").correlated().times(1));
    }

    @Test
    void willCorrelateByBusinessKeyForAReceiveTaskSetsProcessVariables() {
        nudge.simulate(message("ItConfirmed").willCorrelateBy(businessKey(), withVariables(Map.of("confirmed", true))));

        String processInstanceId = testClient.startProcess("it-receive-task", "receive-order", Map.of());
        testClient.awaitEnded(processInstanceId, AWAIT_ENDED_TIMEOUT);

        assertThat(testClient.historicVariables(processInstanceId)).containsEntry("confirmed", true);
        nudge.verify(message("ItConfirmed").correlated().times(1));
    }

    @Test
    void duplicateBusinessKeyAcrossTwoInstancesIsActionFailedNeverRetried() {
        nudge.simulate(message("ItConfirmed").willCorrelateBy(businessKey()));

        testClient.startProcess("it-message-catch", "dup-key", Map.of());
        testClient.startProcess("it-message-catch", "dup-key", Map.of());

        awaitAtLeastNActionErrors(nudge, 2, AWAIT_ENDED_TIMEOUT);

        List<JournalEntry> actionErrors = nudge.actionErrors();
        assertThat(actionErrors).hasSize(2);
        for (JournalEntry entry : actionErrors) {
            assertThat(entry.outcome()).isEqualTo(Outcome.ACTION_FAILED);
            assertThat(entry.error()).isPresent();
            assertThat(entry.error().orElseThrow()).containsIgnoringCase("correlat");
        }

        // Never retried: force one more fresh iteration and confirm no further attempts.
        nudge.verify(message("ItConfirmed").correlated().never());
        assertThat(nudge.actionErrors()).hasSize(2);
    }

    @Test
    void messageStartEventSubscriptionIsIgnoredRatherThanCorrelated() {
        nudge.simulate(message("ItStartConfirmed").willCorrelate());

        // No process instance exists yet, so there is nothing to correlate to; a fresh
        // iteration confirms the (nonexistent, definition-scoped) subscription is never
        // discovered/journaled at all, let alone correlated.
        nudge.verify(message("ItStartConfirmed").reached().never());

        assertThat(testClient.activeProcessInstanceCount("it-message-start")).isZero();
    }

    private static void awaitAtLeastNActionErrors(TokenNudge target, int n, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (target.actionErrors().size() >= n) {
                return;
            }
            sleep(Duration.ofMillis(50));
        }
        throw new AssertionError("fewer than " + n + " action errors appeared within " + timeout);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }
}
