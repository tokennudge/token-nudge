package io.github.tokennudge.camunda7;

import io.github.tokennudge.TokenNudge;
import io.github.tokennudge.VerificationException;
import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.spi.EngineAccessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.tokennudge.TokenNudge.externalTask;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration tests for {@link Camunda7EngineAdapter} and
 * {@link Camunda7AdapterProvider}, driven entirely through the public
 * {@code io.github.tokennudge.TokenNudge} API (ServiceLoader resolution via
 * {@link TokenNudge#forEngine(String)}), against the shared {@link EngineContainer}.
 *
 * <p>Covers PLAN.md §5 item 6a's "done" criteria: completion with variables lands in
 * history, {@code inProcess} filtering leaves the non-matching same-topic task unlocked and
 * journals it UNMATCHED exactly once, a rule added after the token is already waiting still
 * applies, {@code verify(...).times(n)} passes/fails as expected with a useful message,
 * {@code reset()} stops handling, and a wrong engine-rest URL fails {@code start()} fast with
 * a clear message.
 */
class Camunda7EngineAdapterIT {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration AWAIT_ENDED_TIMEOUT = Duration.ofSeconds(10);

    private static EngineRestTestClient testClient;

    @BeforeAll
    static void deployFixtures() {
        String baseUrl = EngineContainer.sharedInstance().baseUrl();
        testClient = new EngineRestTestClient(baseUrl);
        testClient.deploy(
                "bpmn/it-external-simple.bpmn",
                "bpmn/it-same-topic-a.bpmn",
                "bpmn/it-same-topic-b.bpmn");
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
        nudge.simulate(externalTask("it-charge")
                .willComplete(withVariables(Map.of("paid", true, "amount", 4200))));

        String processInstanceId = testClient.startProcess("it-external-simple", "it-charge-order", Map.of());
        testClient.awaitEnded(processInstanceId, AWAIT_ENDED_TIMEOUT);

        Map<String, Object> variables = testClient.historicVariables(processInstanceId);
        assertThat(variables).containsEntry("paid", true).containsEntry("amount", 4200);
    }

    @Test
    void inProcessFilterLeavesTheOtherSameTopicTaskUnlockedAndUnmatchedOnce() {
        nudge.simulate(externalTask("it-same-topic").inProcess("it-same-topic-a").willComplete());

        String processInstanceA = testClient.startProcess("it-same-topic-a", "same-topic-a", Map.of());
        String processInstanceB = testClient.startProcess("it-same-topic-b", "same-topic-b", Map.of());

        testClient.awaitEnded(processInstanceA, AWAIT_ENDED_TIMEOUT);

        // Give the loop a chance to discover and (not) touch B's task at least once more,
        // then assert it is still untouched: a non-monotonic verify waits for one full fresh
        // iteration before evaluating, so this is not a fixed sleep.
        nudge.verify(externalTask("it-same-topic").inProcess("it-same-topic-b").completed().never());

        List<ExternalTaskDto> tasksB = testClient.externalTasks(processInstanceB);
        assertThat(tasksB).hasSize(1);
        assertThat(tasksB.get(0).workerId()).isNull();
        assertThat(tasksB.get(0).lockExpirationTime()).isNull();

        long unmatchedForB = nudge.unmatched().stream()
                .filter(entry -> entry.waitState().processInstanceId().equals(processInstanceB))
                .count();
        assertThat(unmatchedForB).isEqualTo(1);
    }

    @Test
    void aRuleAddedAfterTheTokenIsAlreadyWaitingStillApplies() {
        String processInstanceId = testClient.startProcess("it-external-simple", "late-rule-order", Map.of());
        assertThat(testClient.externalTasks(processInstanceId)).hasSize(1);

        nudge.simulate(externalTask("it-charge").willComplete());

        testClient.awaitEnded(processInstanceId, AWAIT_ENDED_TIMEOUT);
    }

    @Test
    void verifyTimesPassesForOneCompletionAndFailsWithAUsefulMessageForTwo() {
        nudge.simulate(externalTask("it-charge").willComplete());

        String processInstanceId =
                testClient.startProcess("it-external-simple", "verify-times-order", Map.of("amount", 4200));
        testClient.awaitEnded(processInstanceId, AWAIT_ENDED_TIMEOUT);

        nudge.verify(externalTask("it-charge").completed().times(1).withVariable("amount", 4200));

        assertThatThrownBy(() -> nudge.verify(
                externalTask("it-charge").completed().times(2).withVariable("amount", 4200)
                        .within(Duration.ofMillis(500))))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("exactly 2")
                .hasMessageContaining("1 time(s)");
    }

    @Test
    void resetStopsHandlingAndLeavesAnEmptyJournal() {
        nudge.simulate(externalTask("it-charge").willComplete());
        String handledProcessInstance = testClient.startProcess("it-external-simple", "before-reset-order", Map.of());
        nudge.verify(externalTask("it-charge").inProcess("it-external-simple").completed().times(1));
        testClient.awaitEnded(handledProcessInstance, AWAIT_ENDED_TIMEOUT);

        nudge.reset();

        String afterResetProcessInstance =
                testClient.startProcess("it-external-simple", "after-reset-order", Map.of());
        // No simulation is registered after reset(); this call still forces the evaluator to
        // wait for one full fresh loop iteration before evaluating (no fixed sleep), giving a
        // real guarantee that the loop had a chance to run and did not touch the task.
        nudge.verify(externalTask("it-charge").completed().never());

        List<ExternalTaskDto> tasks = testClient.externalTasks(afterResetProcessInstance);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).workerId()).isNull();
        assertThat(tasks.get(0).lockExpirationTime()).isNull();
        assertThat(nudge.journal()).isEmpty();
    }

    @Test
    void forEngineWithAWrongPathFailsFastWithAClearEndpointNotFoundMessage() {
        String wrongUrl = EngineContainer.sharedInstance().baseUrl().replace("/engine-rest", "/wrong-path");

        assertThatThrownBy(() -> TokenNudge.forEngine(wrongUrl)
                .pollInterval(POLL_INTERVAL)
                .start())
                .isInstanceOf(EngineAccessException.class)
                .hasMessageContaining("endpoint not found");
    }
}
