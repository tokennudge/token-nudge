package io.github.tokennudge.camunda7;

import com.sun.net.httpserver.HttpServer;
import io.github.tokennudge.CorrelationStrategy;
import io.github.tokennudge.Variables;
import io.github.tokennudge.model.CompleteUserTask;
import io.github.tokennudge.model.CorrelateMessage;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.spi.EngineActionException;
import io.github.tokennudge.spi.EngineConfig;
import io.github.tokennudge.spi.EngineWaitStateGoneException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the "lost-race gap" fix: {@link Camunda7EngineAdapter#execute} translating an
 * engine rejection classified {@link CamundaFailureClassification#RESOURCE_MISSING} into
 * {@link EngineWaitStateGoneException} for {@link CompleteUserTask}/{@link CorrelateMessage},
 * since {@link Camunda7EngineAdapter#claim} is a local no-op for both kinds (see its Javadoc)
 * and so cannot itself detect the race. Both kinds' actual rejection shapes for an
 * already-gone target are reproduced verbatim (confirmed against real Camunda 7.24.0 and CIB
 * Seven 2.2.0 containers): a user-task completion comes back as a {@code 500} "Cannot find
 * task with id ..." (unlike the external-task endpoints' {@code 404}), and a message
 * correlation comes back as a {@code 400} "No process definition or execution matches the
 * parameters", distinct from the "matches more than one execution" ambiguous-correlation
 * shape. Exercises the real HTTP path (unlike {@link Camunda7EngineAdapterTest}'s pure mapping
 * tests) against a local {@link HttpServer}, the same style as {@link EngineRestClientTest}.
 */
class Camunda7EngineAdapterGoneRaceTest {

    private HttpServer server;
    private Camunda7EngineAdapter adapter;

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void completeUserTaskOn500CannotFindTaskBecomesEngineWaitStateGoneException() throws IOException {
        // The engine's real shape for completing an already-completed or unknown user task id
        // (confirmed against Camunda 7.24.0 and CIB Seven 2.2.0): a 500, not a 404, unlike the
        // external-task endpoints.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/task/task-1/complete", exchange -> sendJson(
                exchange, 500, "{\"type\":\"RestException\",\"message\":\"Cannot complete task task-1: Cannot find "
                        + "task with id task-1: task is null\",\"code\":0}"));
        server.start();
        adapter = newAdapter();
        WaitState userTask = new WaitState(
                WaitStateKind.USER_TASK, "task-1", "review", "pi-1", "it-review", "review", "order-1", "pi-1", null);

        assertThatThrownBy(() -> adapter.execute(userTask, new CompleteUserTask(Variables.empty())))
                .isInstanceOf(EngineWaitStateGoneException.class)
                .isNotInstanceOf(EngineActionException.class)
                .hasMessageContaining("Cannot find task with id");
    }

    @Test
    void correlateMessageOnNoExecutionMatchesBecomesEngineWaitStateGoneException() throws IOException {
        // The engine's real shape for correlating a message whose subscription (or owning
        // process instance) is already gone (confirmed against Camunda 7.24.0 and CIB Seven
        // 2.2.0): a 400, distinct from the "matches more than one execution" ambiguous case
        // exercised below.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/message", exchange -> sendJson(
                exchange, 400, "{\"type\":\"RestException\",\"message\":\"org.camunda.bpm.engine."
                        + "MismatchingMessageCorrelationException: Cannot correlate message 'PaymentConfirmed': "
                        + "No process definition or execution matches the parameters\",\"code\":0}"));
        server.start();
        adapter = newAdapter();
        WaitState message = new WaitState(
                WaitStateKind.MESSAGE_SUBSCRIPTION, "sub-1", "PaymentConfirmed", "pi-1", "it-message-catch",
                "confirm", "order-1", "pi-1", null);
        CorrelateMessage correlate = new CorrelateMessage(new CorrelationStrategy.ByProcessInstance(), Variables.empty());

        assertThatThrownBy(() -> adapter.execute(message, correlate))
                .isInstanceOf(EngineWaitStateGoneException.class)
                .isNotInstanceOf(EngineActionException.class)
                .hasMessageContaining("No process definition or execution matches the parameters");
    }

    @Test
    void completeUserTaskOnOtherRejectionStaysAnEngineActionException() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/task/task-1/complete", exchange -> sendJson(
                exchange, 500, "{\"type\":\"ProcessEngineException\",\"message\":\"unexpected failure\","
                        + "\"code\":0}"));
        server.start();
        adapter = newAdapter();
        WaitState userTask = new WaitState(
                WaitStateKind.USER_TASK, "task-1", "review", "pi-1", "it-review", "review", "order-1", "pi-1", null);

        assertThatThrownBy(() -> adapter.execute(userTask, new CompleteUserTask(Variables.empty())))
                .isInstanceOf(EngineActionException.class)
                .isNotInstanceOf(EngineWaitStateGoneException.class);
    }

    @Test
    void correlateMessageOnAmbiguousCorrelationStaysAnEngineActionException() throws IOException {
        // The engine's real shape for two process instances sharing a business key (confirmed
        // against Camunda 7.24.0 and CIB Seven 2.2.0): also a 400 RestException, but with a
        // distinct "matches more than one execution" message, which must not be classified as
        // RESOURCE_MISSING/EngineWaitStateGoneException even though it shares the status code
        // and type with the "gone" case above.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/message", exchange -> sendJson(
                exchange, 400, "{\"type\":\"RestException\",\"message\":\"org.camunda.bpm.engine."
                        + "MismatchingMessageCorrelationException: ENGINE-13031 Cannot correlate a message with "
                        + "name 'PaymentConfirmed' to a single execution. 2 executions match the correlation keys: "
                        + "CorrelationSet [businessKey=order-1]\",\"code\":0}"));
        server.start();
        adapter = newAdapter();
        WaitState message = new WaitState(
                WaitStateKind.MESSAGE_SUBSCRIPTION, "sub-1", "PaymentConfirmed", "pi-1", "it-message-catch",
                "confirm", "order-1", "pi-1", null);
        CorrelateMessage correlate = new CorrelateMessage(new CorrelationStrategy.ByBusinessKey(), Variables.empty());

        assertThatThrownBy(() -> adapter.execute(message, correlate))
                .isInstanceOf(EngineActionException.class)
                .isNotInstanceOf(EngineWaitStateGoneException.class)
                .hasMessageContaining("to a single execution");
    }

    private Camunda7EngineAdapter newAdapter() {
        return new Camunda7EngineAdapter(new EngineConfig(
                baseUri(server), Duration.ofSeconds(2), Duration.ofSeconds(30), "worker-1", 50, Map.of()));
    }

    private static URI baseUri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
