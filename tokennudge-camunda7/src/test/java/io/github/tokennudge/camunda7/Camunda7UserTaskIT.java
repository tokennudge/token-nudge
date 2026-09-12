package io.github.tokennudge.camunda7;

import io.github.tokennudge.TokenNudge;
import io.github.tokennudge.VerificationException;
import io.github.tokennudge.camunda7.dto.TaskDto;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
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

import static io.github.tokennudge.TokenNudge.userTask;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration tests for {@link Camunda7EngineAdapter}'s user-task path (discovery
 * via {@code POST /task}, enrichment via {@link ProcessInstanceResolver}, completion via
 * {@code POST /task/{id}/complete}), driven entirely through the public
 * {@code io.github.tokennudge.TokenNudge} API. Covers PLAN.md §5 item 9's "done" criteria.
 */
class Camunda7UserTaskIT {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration AWAIT_ENDED_TIMEOUT = Duration.ofSeconds(10);

    private static EngineRestTestClient testClient;

    @BeforeAll
    static void deployFixtures() {
        String baseUrl = EngineContainer.sharedInstance().baseUrl();
        testClient = new EngineRestTestClient(baseUrl);
        testClient.deploy("bpmn/it-user-task.bpmn", "bpmn/it-user-task-b.bpmn");
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
    void completionWithVariablesEndsTheProcessAndSetsHistoricVariables() {
        nudge.simulate(userTask("review").willComplete(withVariables(Map.of("approved", true, "score", 42))));

        String processInstanceId = testClient.startProcess("it-user-task", "review-order", Map.of());
        testClient.awaitEnded(processInstanceId, AWAIT_ENDED_TIMEOUT);

        Map<String, Object> variables = testClient.historicVariables(processInstanceId);
        assertThat(variables).containsEntry("approved", true).containsEntry("score", 42);
    }

    @Test
    void inProcessFilterLeavesTheOtherProcessTaskOpenAndUnmatchedOnce() {
        nudge.simulate(userTask("review").inProcess("it-user-task").willComplete());

        String processInstanceA = testClient.startProcess("it-user-task", "review-a", Map.of());
        String processInstanceB = testClient.startProcess("it-user-task-b", "review-b", Map.of());

        testClient.awaitEnded(processInstanceA, AWAIT_ENDED_TIMEOUT);

        // Non-monotonic verify waits for one full fresh iteration before evaluating, giving a
        // real guarantee that the loop had a chance to (not) touch B's task, no fixed sleep.
        nudge.verify(userTask("review").inProcess("it-user-task-b").completed().never());

        List<TaskDto> tasksB = testClient.userTasks(processInstanceB);
        assertThat(tasksB).hasSize(1);

        long unmatchedForB = nudge.unmatched().stream()
                .filter(entry -> entry.waitState().processInstanceId().equals(processInstanceB))
                .count();
        assertThat(unmatchedForB).isEqualTo(1);
    }

    @Test
    void withBusinessKeyFilterCompletesOnlyTheMatchingBusinessKey() {
        nudge.simulate(userTask("review").withBusinessKey("bk-a").willComplete());

        String processInstanceA = testClient.startProcess("it-user-task", "bk-a", Map.of());
        String processInstanceB = testClient.startProcess("it-user-task", "bk-b", Map.of());

        testClient.awaitEnded(processInstanceA, AWAIT_ENDED_TIMEOUT);
        nudge.verify(userTask("review").withBusinessKey("bk-b").completed().never());

        assertThat(testClient.userTasks(processInstanceB)).hasSize(1);
    }

    @Test
    void verifyCompletedTimesOnePasses() {
        nudge.simulate(userTask("review").willComplete());

        String processInstanceId = testClient.startProcess("it-user-task", "verify-times-order", Map.of());
        testClient.awaitEnded(processInstanceId, AWAIT_ENDED_TIMEOUT);

        nudge.verify(userTask("review").completed().times(1));

        assertThatThrownBy(() -> nudge.verify(
                userTask("review").completed().times(2).within(Duration.ofMillis(500))))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("exactly 2");
    }

    @Test
    void aTaskThatNoRuleMatchesStaysOpenAndIsJournaledUnmatchedOnce() {
        // Registering a rule for a *different* topic keeps the loop's discovery targeting
        // "review" alive isn't needed here: the loop only discovers names referenced by at
        // least one registered rule, so a rule for this exact task definition key that never
        // statically matches (a business key nothing will ever have) is what makes the engine
        // discover it while still leaving it unmatched.
        nudge.simulate(userTask("review").withBusinessKey("nobody-has-this-key").willComplete());

        String processInstanceId = testClient.startProcess("it-user-task", "unmatched-order", Map.of());
        nudge.verify(userTask("review").reached().times(1));

        assertThat(testClient.userTasks(processInstanceId)).hasSize(1);
        long unmatchedCount = nudge.unmatched().stream()
                .filter(entry -> entry.waitState().processInstanceId().equals(processInstanceId))
                .count();
        assertThat(unmatchedCount).isEqualTo(1);
    }

    /**
     * The lost-race gap (see {@code EngineWaitStateGoneException}): completes the user task
     * out of band, through {@link CompleteUserTaskBeforeExecuteAdapter}, between this worker's
     * discovery/claim and its own {@code execute} call, reproducing the real engine's
     * {@code 500} "Cannot find task with id ..." response for the loop's own completion attempt. Confirms
     * the loop journals {@code CLAIM_LOST}, not an action error, and never retries.
     */
    @Test
    void aUserTaskCompletedOutOfBandBetweenDiscoveryAndExecuteIsJournaledClaimLost() {
        EngineConfig config = new EngineConfig(
                URI.create(EngineContainer.sharedInstance().baseUrl()),
                Duration.ofSeconds(10), Duration.ofSeconds(30), "it-race-worker-" + UUID.randomUUID(), 50, Map.of());
        CompleteUserTaskBeforeExecuteAdapter adapter = new CompleteUserTaskBeforeExecuteAdapter(config, testClient);
        TokenNudge racingNudge = TokenNudge.forAdapter(adapter)
                .pollInterval(POLL_INTERVAL)
                .verifyTimeout(VERIFY_TIMEOUT)
                .start();
        try {
            racingNudge.simulate(userTask("review").willComplete());
            testClient.startProcess("it-user-task", "race-order", Map.of());

            awaitAtLeastOneEntry(racingNudge, AWAIT_ENDED_TIMEOUT);

            assertThat(racingNudge.actionErrors()).isEmpty();
            List<JournalEntry> entries = racingNudge.journal();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).outcome()).isEqualTo(Outcome.CLAIM_LOST);

            // Never retried: force one more fresh iteration and confirm no second attempt.
            racingNudge.verify(userTask("review").completed().never());
            assertThat(racingNudge.journal()).hasSize(1);
        } finally {
            racingNudge.stop();
        }
    }

    private static void awaitAtLeastOneEntry(TokenNudge target, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!target.journal().isEmpty()) {
                return;
            }
            sleep(Duration.ofMillis(50));
        }
        throw new AssertionError("no journal entry appeared within " + timeout);
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
