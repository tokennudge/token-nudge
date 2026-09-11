package io.github.tokennudge.camunda7;

import io.github.tokennudge.TokenNudge;
import io.github.tokennudge.VerificationException;
import io.github.tokennudge.camunda7.dto.TaskDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
}
