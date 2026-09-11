package io.github.tokennudge.camunda7;

import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.camunda7.dto.ExternalTaskQueryRequest;
import io.github.tokennudge.spi.EngineConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A smoke test proving the whole IT harness works end to end: deploys every BPMN fixture
 * against the shared {@link EngineContainer}, starts a process instance, and exercises
 * {@link EngineRestClient} directly (the class under test), independently of
 * {@link EngineRestTestClient} (the harness).
 */
class EngineSmokeIT {

    private static EngineRestTestClient testClient;
    private static EngineRestClient client;

    @BeforeAll
    static void deployFixtures() {
        String baseUrl = EngineContainer.sharedInstance().baseUrl();
        testClient = new EngineRestTestClient(baseUrl);
        client = new EngineRestClient(new EngineConfig(
                URI.create(baseUrl), Duration.ofSeconds(10), Duration.ofSeconds(30), "it-worker", 50, Map.of()));
        testClient.deploy(
                "bpmn/it-external-simple.bpmn",
                "bpmn/it-external-bpmn-error.bpmn",
                "bpmn/it-same-topic-a.bpmn",
                "bpmn/it-same-topic-b.bpmn",
                "bpmn/it-user-task.bpmn",
                "bpmn/it-message-catch.bpmn",
                "bpmn/it-receive-task.bpmn");
    }

    @AfterEach
    void deleteProcessInstances() {
        testClient.deleteAllProcessInstances();
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    @Test
    void discoversAnExternalTaskWithBusinessKeyAndProcessDefinitionKeyPopulated() {
        testClient.startProcess("it-external-simple", "order-1", Map.of());

        ExternalTaskDto[] tasks = client.postJson("/external-task",
                new ExternalTaskQueryRequest("it-charge", true, true, true), ExternalTaskDto[].class);

        assertThat(tasks).hasSize(1);
        assertThat(tasks[0].businessKey()).isEqualTo("order-1");
        assertThat(tasks[0].processDefinitionKey()).isEqualTo("it-external-simple");
    }

    @Test
    void reportsTheEngineVersion() {
        VersionDto version = client.getJson("/version", VersionDto.class);
        assertThat(version.version()).isNotBlank();
    }

    private record VersionDto(String version) {
    }
}
