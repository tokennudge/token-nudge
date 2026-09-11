package io.github.tokennudge.camunda7;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.camunda7.dto.TaskDto;
import io.github.tokennudge.camunda7.dto.TypedValueDto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A minimal, independent engine-rest client used only by integration tests to set up and
 * inspect engine state (deploy BPMN, start process instances, poll history). Deliberately
 * separate from {@link EngineRestClient}, the class under test.
 */
final class EngineRestTestClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;

    EngineRestTestClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Deploys one or more BPMN files found on the test classpath (for example
     * {@code "bpmn/it-external-simple.bpmn"}), with duplicate filtering enabled so repeated
     * calls across tests are cheap.
     *
     * @param classpathBpmn classpath-relative resource paths of the BPMN files to deploy
     */
    void deploy(String... classpathBpmn) {
        String boundary = "tokennudge-it-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, classpathBpmn);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/deployment/create"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        send(request, 200);
    }

    private byte[] multipartBody(String boundary, String[] classpathBpmn) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, boundary, "deployment-name", "tokennudge-it");
        writeField(out, boundary, "enable-duplicate-filtering", "true");
        writeField(out, boundary, "deploy-changed-only", "true");
        for (String resource : classpathBpmn) {
            writeFile(out, boundary, resource);
        }
        writeAscii(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private void writeField(ByteArrayOutputStream out, String boundary, String name, String value) {
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeAscii(out, value + "\r\n");
    }

    private void writeFile(ByteArrayOutputStream out, String boundary, String classpathResource) {
        String fileName = classpathResource.substring(classpathResource.lastIndexOf('/') + 1);
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"" + fileName
                + "\"; filename=\"" + fileName + "\"\r\n");
        writeAscii(out, "Content-Type: text/xml\r\n\r\n");
        try (InputStream in = requireResource(classpathResource)) {
            out.write(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        writeAscii(out, "\r\n");
    }

    private InputStream requireResource(String classpathResource) {
        InputStream in = getClass().getClassLoader().getResourceAsStream(classpathResource);
        if (in == null) {
            throw new IllegalArgumentException("test BPMN fixture not found on classpath: " + classpathResource);
        }
        return in;
    }

    private void writeAscii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Starts a process instance by process definition key.
     *
     * @param processDefinitionKey the process definition key
     * @param businessKey          the business key, or {@code null} for none
     * @param variables            the start variables, keyed by name
     * @return the started process instance's id
     */
    String startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables) {
        Map<String, TypedValueDto> encoded = new LinkedHashMap<>();
        variables.forEach((name, value) -> encoded.put(name, VariableCodec.encode(value)));
        Map<String, Object> body = new LinkedHashMap<>();
        if (businessKey != null) {
            body.put("businessKey", businessKey);
        }
        body.put("variables", encoded);
        JsonNode response = postJson("/process-definition/key/" + processDefinitionKey + "/start", body, 200);
        return response.get("id").asText();
    }

    /**
     * Polls process instance history until the process instance has ended.
     *
     * @param processInstanceId the process instance id
     * @param timeout           how long to wait before failing
     */
    void awaitEnded(String processInstanceId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            JsonNode historic = getJson("/history/process-instance/" + processInstanceId, 200);
            if (!historic.path("endTime").isMissingNode() && !historic.path("endTime").isNull()) {
                return;
            }
            sleep(Duration.ofMillis(100));
        }
        throw new AssertionError("process instance " + processInstanceId + " did not end within " + timeout);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }

    /**
     * Returns the historic variables of a (possibly still running) process instance.
     *
     * @param processInstanceId the process instance id
     * @return the variables, keyed by name, decoded to Java values
     */
    Map<String, Object> historicVariables(String processInstanceId) {
        JsonNode array = getJson("/history/variable-instance?processInstanceId=" + processInstanceId, 200);
        Map<String, Object> result = new LinkedHashMap<>();
        for (JsonNode node : array) {
            String type = node.path("type").isMissingNode() ? null : node.path("type").asText(null);
            Object rawValue = JsonSupport.MAPPER.convertValue(node.path("value"), Object.class);
            result.put(node.get("name").asText(), VariableCodec.decode(type, rawValue));
        }
        return result;
    }

    /**
     * Returns the open incidents for a process instance.
     *
     * @param processInstanceId the process instance id
     * @return the open incidents; empty if none
     */
    List<IncidentInfo> incidents(String processInstanceId) {
        JsonNode array = getJson("/incident?processInstanceId=" + processInstanceId, 200);
        List<IncidentInfo> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(new IncidentInfo(node.get("incidentType").asText(), node.path("incidentMessage").asText(null)));
        }
        return result;
    }

    /**
     * An open incident's type and message, as returned by {@code GET /incident}.
     *
     * @param incidentType    the incident type, for example {@code "failedExternalTask"}
     * @param incidentMessage the incident's message, or {@code null}
     */
    record IncidentInfo(String incidentType, String incidentMessage) {
    }

    /**
     * Returns the external tasks currently attached to a process instance.
     *
     * @param processInstanceId the process instance id
     * @return the external tasks
     */
    List<ExternalTaskDto> externalTasks(String processInstanceId) {
        JsonNode array = getJson("/external-task?processInstanceId=" + processInstanceId, 200);
        List<ExternalTaskDto> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(JsonSupport.MAPPER.convertValue(node, ExternalTaskDto.class));
        }
        return result;
    }

    /**
     * Returns the user tasks currently open on a process instance.
     *
     * @param processInstanceId the process instance id
     * @return the open user tasks
     */
    List<TaskDto> userTasks(String processInstanceId) {
        JsonNode array = getJson("/task?processInstanceId=" + processInstanceId, 200);
        List<TaskDto> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(JsonSupport.MAPPER.convertValue(node, TaskDto.class));
        }
        return result;
    }

    /**
     * Returns the activity ids of every historic activity instance for a process instance,
     * in the order the engine reports them, so a test can confirm which path (for example a
     * boundary error's distinct end event) was actually taken.
     *
     * @param processInstanceId the process instance id
     * @return the historic activity ids
     */
    List<String> historicActivityIds(String processInstanceId) {
        JsonNode array = getJson("/history/activity-instance?processInstanceId=" + processInstanceId, 200);
        List<String> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(node.get("activityId").asText());
        }
        return result;
    }

    /**
     * Returns how many process instances currently exist for the given process definition
     * key, so a test can confirm that a message start event was never triggered.
     *
     * @param processDefinitionKey the process definition key
     * @return the number of running process instances
     */
    int activeProcessInstanceCount(String processDefinitionKey) {
        JsonNode array = getJson("/process-instance?processDefinitionKey=" + processDefinitionKey, 200);
        return array.size();
    }

    /**
     * Deletes a single process instance, so a test can simulate the engine-side state
     * disappearing between an adapter's {@code claim} and {@code execute} calls.
     *
     * @param processInstanceId the process instance id to delete
     */
    void deleteProcessInstance(String processInstanceId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/process-instance/" + processInstanceId
                        + "?skipCustomListeners=true&skipIoMappings=true"))
                .DELETE()
                .build();
        send(request, 204);
    }

    /**
     * Deletes every process instance currently running on the engine, so that tests do not
     * interfere with each other. Intended for use in {@code @AfterEach}.
     */
    void deleteAllProcessInstances() {
        JsonNode array = getJson("/process-instance", 200);
        for (JsonNode node : array) {
            deleteProcessInstance(node.get("id").asText());
        }
    }

    private JsonNode getJson(String path, int expectedStatus) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .GET()
                .build();
        return readJson(send(request, expectedStatus));
    }

    private JsonNode postJson(String path, Object body, int expectedStatus) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        return readJson(send(request, expectedStatus));
    }

    private HttpRequest.BodyPublisher jsonBody(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofByteArray(JsonSupport.MAPPER.writeValueAsBytes(body));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode readJson(HttpResponse<byte[]> response) {
        try {
            return JsonSupport.MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request, int expectedStatus) {
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode())
                    .describedAs("response for %s %s: %s", request.method(), request.uri(),
                            new String(response.body(), StandardCharsets.UTF_8))
                    .isEqualTo(expectedStatus);
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while calling the test engine", e);
        }
    }
}
