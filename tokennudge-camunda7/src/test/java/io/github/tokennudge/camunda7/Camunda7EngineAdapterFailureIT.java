package io.github.tokennudge.camunda7;

import io.github.tokennudge.TokenNudge;
import io.github.tokennudge.Variables;
import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.camunda7.dto.LockExternalTaskRequest;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.spi.ClaimResult;
import io.github.tokennudge.spi.EngineConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.tokennudge.TokenNudge.externalTask;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link Camunda7EngineAdapter}'s iteration 6b execute paths (BPMN
 * error and failure) and for {@code CLAIM_LOST} at the adapter level, against a real engine.
 * Covers PLAN.md §5 item 6b's "done" criteria.
 */
class Camunda7EngineAdapterFailureIT {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    private static EngineRestTestClient testClient;

    @BeforeAll
    static void deployFixtures() {
        String baseUrl = EngineContainer.sharedInstance().baseUrl();
        testClient = new EngineRestTestClient(baseUrl);
        testClient.deploy("bpmn/it-external-simple.bpmn", "bpmn/it-external-bpmn-error.bpmn");
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

    private static EngineConfig engineConfig(String workerId) {
        return new EngineConfig(
                URI.create(EngineContainer.sharedInstance().baseUrl()),
                Duration.ofSeconds(10), Duration.ofSeconds(30), workerId, 50, Map.of());
    }

    @Test
    void bpmnErrorTakesTheBoundaryPath() {
        nudge.simulate(externalTask("it-charge-error").willFailWithBpmnError(
                "IT_REJECTED", "rejected by test", withVariables(Map.of("reason", "fraud"))));

        String processInstanceId = testClient.startProcess("it-external-bpmn-error", "bpmn-error-order", Map.of());
        testClient.awaitEnded(processInstanceId, AWAIT_TIMEOUT);

        List<String> activityIds = testClient.historicActivityIds(processInstanceId);
        assertThat(activityIds).contains("rejectedBoundary", "rejectedEnd").doesNotContain("end");
        assertThat(testClient.historicVariables(processInstanceId)).containsEntry("reason", "fraud");

        nudge.verify(externalTask("it-charge-error").failedWithBpmnError("IT_REJECTED").times(1));
    }

    @Test
    void failureWithZeroRetriesCreatesAnIncident() {
        nudge.simulate(externalTask("it-charge").willFail("boom"));

        String processInstanceId = testClient.startProcess("it-external-simple", "fail-incident-order", Map.of());
        nudge.verify(externalTask("it-charge").failed().times(1));

        List<EngineRestTestClient.IncidentInfo> incidents = testClient.incidents(processInstanceId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).incidentType()).isEqualTo("failedExternalTask");
        assertThat(incidents.get(0).incidentMessage()).contains("boom");

        // The process is still running: the failed external task (with an open incident)
        // still exists, rather than the process having reached its end event.
        List<ExternalTaskDto> tasks = testClient.externalTasks(processInstanceId);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).retries()).isEqualTo(0);
    }

    @Test
    void failureWithRetriesLeftLeavesTheTaskLockedUntilTheRetryTimeout() {
        nudge.simulate(externalTask("it-charge").willFail("transient", 2, Duration.ofMinutes(5)));

        String processInstanceId = testClient.startProcess("it-external-simple", "fail-retry-order", Map.of());
        nudge.verify(externalTask("it-charge").failed().times(1));

        assertThat(testClient.incidents(processInstanceId)).isEmpty();
        List<ExternalTaskDto> tasks = testClient.externalTasks(processInstanceId);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).retries()).isEqualTo(2);

        // The engine sets a 5-minute lockExpirationTime on a failure with a retryTimeout, so
        // the task is not fetchable again within this test's lifetime; no dedicated wait is
        // needed to prove it is not re-handled in this run. The journal having exactly one
        // entry for this task id confirms the loop did not act on it twice.
        long entriesForThisTask = nudge.journal().stream()
                .filter(entry -> entry.waitState().id().equals(tasks.get(0).id()))
                .count();
        assertThat(entriesForThisTask).isEqualTo(1);
    }

    /**
     * Deletes the process instance between {@code claim} and {@code execute} (via
     * {@link DeleteProcessInstanceBeforeExecuteAdapter}, a test-only wrapper around the real
     * {@link Camunda7EngineAdapter}) to reproduce a deterministic engine rejection of a
     * completion request. This was chosen over the alternatives considered:
     * <ul>
     *   <li>{@code willFailWithBpmnError} with no matching boundary event: observed against
     *       both Camunda 7.24.0 and CIB Seven 2.2.0 to simply end the wait state's scope with
     *       a {@code 204}, not reject the request.</li>
     *   <li>Completing with an engine-rejected variable value: every value
     *       {@link VariableCodec#encode(Object)} accepts is also accepted by the engine, so
     *       this never reaches the HTTP call at all (it would instead be a pre-send encoding
     *       failure, already covered by {@code Camunda7EngineAdapterTest}'s
     *       {@code *EncodeFailureIsWrappedAsADefiniteEngineActionException} tests).</li>
     * </ul>
     */
    @Test
    void engineRejectedActionEndsUpInActionErrors() {
        EngineConfig config = engineConfig("it-reject-worker-" + UUID.randomUUID());
        DeleteProcessInstanceBeforeExecuteAdapter adapter =
                new DeleteProcessInstanceBeforeExecuteAdapter(config, testClient);
        TokenNudge rejectingNudge = TokenNudge.forAdapter(adapter)
                .pollInterval(POLL_INTERVAL)
                .verifyTimeout(VERIFY_TIMEOUT)
                .start();
        try {
            rejectingNudge.simulate(externalTask("it-charge").willComplete());
            testClient.startProcess("it-external-simple", "engine-rejected-order", Map.of());

            awaitAtLeastOneActionError(rejectingNudge, AWAIT_TIMEOUT);

            List<JournalEntry> actionErrors = rejectingNudge.actionErrors();
            assertThat(actionErrors).hasSize(1);
            JournalEntry entry = actionErrors.get(0);
            assertThat(entry.outcome()).isEqualTo(Outcome.ACTION_FAILED);
            assertThat(entry.error()).isPresent();
            assertThat(entry.error().orElseThrow()).contains("does not exist");

            // Never retried: force one more fresh iteration and confirm no second attempt.
            rejectingNudge.verify(externalTask("it-charge").completed().never());
            assertThat(rejectingNudge.actionErrors()).hasSize(1);
        } finally {
            rejectingNudge.stop();
        }
    }

    private static void awaitAtLeastOneActionError(TokenNudge target, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!target.actionErrors().isEmpty()) {
                return;
            }
            sleep(Duration.ofMillis(50));
        }
        throw new AssertionError("no action error appeared within " + timeout);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }

    /**
     * {@code CLAIM_LOST} at the adapter level: another worker already holds the lock.
     * Loop-level confirmation that {@code CLAIM_LOST} never lands in {@code actionErrors()}
     * is already covered by {@code NudgeLoopTest.claimLostIsJournaledAsClaimLostNotAnError}
     * (core, {@link io.github.tokennudge.internal.NudgeLoop}), against a fake adapter.
     */
    @Test
    void claimIsLostWhenAnotherWorkerHoldsTheLock() {
        String processInstanceId = testClient.startProcess("it-external-simple", "claim-lost-locked-order", Map.of());
        ExternalTaskDto dto = testClient.externalTasks(processInstanceId).get(0);

        EngineConfig otherWorkerConfig = engineConfig("other-worker-" + UUID.randomUUID());
        EngineRestClient otherWorkerClient = new EngineRestClient(otherWorkerConfig);
        try {
            otherWorkerClient.postNoContent("/external-task/" + dto.id() + "/lock",
                    new LockExternalTaskRequest(otherWorkerConfig.workerId(), Duration.ofSeconds(30).toMillis()));
        } finally {
            otherWorkerClient.close();
        }

        try (Camunda7EngineAdapter adapter = new Camunda7EngineAdapter(engineConfig("claim-lost-tester-" + UUID.randomUUID()))) {
            WaitState waitState = Camunda7EngineAdapter.toWaitState(dto);
            assertThat(adapter.claim(waitState)).isEqualTo(ClaimResult.LOST);
        }
    }

    /**
     * {@code CLAIM_LOST} at the adapter level: the task was already completed by another
     * worker before this worker's claim attempt.
     */
    @Test
    void claimIsLostWhenTheTaskWasAlreadyCompleted() {
        String processInstanceId =
                testClient.startProcess("it-external-simple", "claim-lost-completed-order", Map.of());
        ExternalTaskDto dto = testClient.externalTasks(processInstanceId).get(0);

        try (Camunda7EngineAdapter completer = new Camunda7EngineAdapter(engineConfig("completer-" + UUID.randomUUID()))) {
            WaitState waitState = Camunda7EngineAdapter.toWaitState(dto);
            assertThat(completer.claim(waitState)).isEqualTo(ClaimResult.CLAIMED);
            completer.execute(waitState, new CompleteExternalTask(Variables.empty()));
        }

        try (Camunda7EngineAdapter lateAdapter = new Camunda7EngineAdapter(engineConfig("late-worker-" + UUID.randomUUID()))) {
            WaitState waitState = Camunda7EngineAdapter.toWaitState(dto);
            assertThat(lateAdapter.claim(waitState)).isEqualTo(ClaimResult.LOST);
        }
    }
}
