package io.github.tokennudge.camunda7;

import io.github.tokennudge.camunda7.dto.CompleteExternalTaskRequest;
import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.camunda7.dto.ExternalTaskQueryRequest;
import io.github.tokennudge.camunda7.dto.LockExternalTaskRequest;
import io.github.tokennudge.spi.EngineActionException;
import io.github.tokennudge.spi.EngineConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Confirms, against a real engine, that {@code POST /external-task/{id}/lock} exists and
 * behaves as documented in {@code docs/PROGRESS.md} ("Known risks"), and that
 * {@link EngineRestClient}/{@link CamundaFailureClassifier} classify its responses
 * correctly. This is the spike that de-risks the fetch-and-lock fallback described in
 * {@code docs/PLAN.md}'s ADR-2 (not needed, since lock-by-id works on both engines).
 */
class ExternalTaskLockSpikeIT {

    private static EngineRestTestClient testClient;
    private static EngineRestClient client;

    @BeforeAll
    static void setUp() {
        String baseUrl = EngineContainer.sharedInstance().baseUrl();
        testClient = new EngineRestTestClient(baseUrl);
        client = new EngineRestClient(new EngineConfig(
                URI.create(baseUrl), Duration.ofSeconds(10), Duration.ofSeconds(30), "it-worker", 50, Map.of()));
        testClient.deploy("bpmn/it-external-simple.bpmn");
    }

    @AfterEach
    void cleanUp() {
        testClient.deleteAllProcessInstances();
    }

    @AfterAll
    static void tearDown() {
        client.close();
    }

    private String startAndDiscoverOneExternalTask() {
        testClient.startProcess("it-external-simple", "spike-" + UUID.randomUUID(), Map.of());
        ExternalTaskDto[] tasks = client.postJson("/external-task",
                new ExternalTaskQueryRequest("it-charge", true, true, true), ExternalTaskDto[].class);
        assertThat(tasks).hasSize(1);
        return tasks[0].id();
    }

    @Test
    void lockingAnUnlockedTaskSucceeds() {
        String id = startAndDiscoverOneExternalTask();

        client.postNoContent("/external-task/" + id + "/lock", new LockExternalTaskRequest("worker-A", 30_000L));
    }

    @Test
    void secondWorkerLockingAnAlreadyLockedTaskIsClassifiedAsLockedByOtherWorker() {
        String id = startAndDiscoverOneExternalTask();
        client.postNoContent("/external-task/" + id + "/lock", new LockExternalTaskRequest("worker-A", 30_000L));

        assertThatThrownBy(() -> client.postNoContent(
                "/external-task/" + id + "/lock", new LockExternalTaskRequest("worker-B", 30_000L)))
                .isInstanceOf(EngineActionException.class)
                .satisfies(e -> {
                    EngineActionException action = (EngineActionException) e;
                    assertThat(action.status()).isEqualTo(400);
                    assertThat(CamundaFailureClassifier.classify(action))
                            .isEqualTo(CamundaFailureClassification.LOCKED_BY_OTHER_WORKER);
                });
    }

    @Test
    void lockingACompletedOrUnknownIdIsClassifiedAsResourceMissing() {
        String id = startAndDiscoverOneExternalTask();
        client.postNoContent("/external-task/" + id + "/lock", new LockExternalTaskRequest("worker-A", 30_000L));
        client.postNoContent("/external-task/" + id + "/complete",
                new CompleteExternalTaskRequest("worker-A", Map.of()));

        assertThatThrownBy(() -> client.postNoContent(
                "/external-task/" + id + "/lock", new LockExternalTaskRequest("worker-A", 30_000L)))
                .isInstanceOf(EngineActionException.class)
                .satisfies(e -> {
                    EngineActionException action = (EngineActionException) e;
                    assertThat(action.status()).isEqualTo(404);
                    assertThat(CamundaFailureClassifier.classify(action))
                            .isEqualTo(CamundaFailureClassification.RESOURCE_MISSING);
                });
    }

    @Test
    void aNonExistentRouteIsClassifiedAsEndpointNotFound() {
        assertThatThrownBy(() -> client.getJson("/no-such-endpoint", Object.class))
                .isInstanceOf(EngineActionException.class)
                .satisfies(e -> {
                    EngineActionException action = (EngineActionException) e;
                    assertThat(action.status()).isEqualTo(404);
                    assertThat(CamundaFailureClassifier.classify(action))
                            .isEqualTo(CamundaFailureClassification.ENDPOINT_NOT_FOUND);
                });
    }

    @Test
    void unlockingALockedTaskSucceeds() {
        String id = startAndDiscoverOneExternalTask();
        client.postNoContent("/external-task/" + id + "/lock", new LockExternalTaskRequest("worker-A", 30_000L));

        client.postNoContent("/external-task/" + id + "/unlock", null);
    }

    @Test
    void completingALockedTaskSucceeds() {
        String id = startAndDiscoverOneExternalTask();
        client.postNoContent("/external-task/" + id + "/lock", new LockExternalTaskRequest("worker-A", 30_000L));

        client.postNoContent("/external-task/" + id + "/complete",
                new CompleteExternalTaskRequest("worker-A", Map.of()));
    }
}
