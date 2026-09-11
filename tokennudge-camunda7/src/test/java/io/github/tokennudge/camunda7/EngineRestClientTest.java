package io.github.tokennudge.camunda7;

import com.sun.net.httpserver.HttpServer;
import io.github.tokennudge.spi.EngineAccessException;
import io.github.tokennudge.spi.EngineActionException;
import io.github.tokennudge.spi.EngineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link EngineRestClient}'s failure-contract mapping without a mocked engine:
 * against a closed port (connection refused) and a local {@link HttpServer}.
 */
class EngineRestClientTest {

    private HttpServer server;
    private EngineRestClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void connectionRefusedBecomesEngineAccessException() throws IOException {
        int freePort = findFreePortWithNoListener();
        client = newClient(URI.create("http://127.0.0.1:" + freePort), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.getJson("/engine", Object.class))
                .isInstanceOf(EngineAccessException.class);
    }

    @Test
    void requestTimeoutAfterConnectingBecomesAmbiguousOutcome() throws IOException {
        CountDownLatch releaseHandler = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hang", exchange -> {
            try {
                releaseHandler.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        client = newClient(baseUri(server), Duration.ofMillis(300));

        assertThatThrownBy(() -> client.getJson("/hang", Object.class))
                .isInstanceOf(EngineOutcomeUnknownException.class)
                .isNotInstanceOf(EngineAccessException.class);

        releaseHandler.countDown();
    }

    @Test
    void badRequestWithLockConflictBodyBecomesClassifiedEngineActionException() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/external-task/1/lock", exchange -> {
            byte[] body = ("{\"type\":\"RestException\",\"message\":\"External Task 1 cannot be locked by worker "
                    + "'worker-B'. It is locked by worker 'worker-A'.\",\"code\":0}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        client = newClient(baseUri(server), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.postNoContent("/external-task/1/lock", Map.of()))
                .isInstanceOf(EngineActionException.class)
                .satisfies(e -> {
                    EngineActionException action = (EngineActionException) e;
                    assertThat(action.status()).isEqualTo(400);
                    assertThat(action.engineErrorType()).isEqualTo("RestException");
                    assertThat(action.engineMessage()).contains("cannot be locked by worker");
                    assertThat(CamundaFailureClassifier.classify(action))
                            .isEqualTo(CamundaFailureClassification.LOCKED_BY_OTHER_WORKER);
                });
    }

    @Test
    void notFoundWithNotFoundExceptionTypeIsClassifiedAsEndpointNotFound() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/no-such-route", exchange -> {
            byte[] body = "{\"type\":\"NotFoundException\",\"message\":\"HTTP 404 Not Found\",\"code\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        client = newClient(baseUri(server), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.getJson("/no-such-route", Object.class))
                .isInstanceOf(EngineActionException.class)
                .satisfies(e -> {
                    EngineActionException action = (EngineActionException) e;
                    assertThat(action.status()).isEqualTo(404);
                    assertThat(CamundaFailureClassifier.classify(action))
                            .isEqualTo(CamundaFailureClassification.ENDPOINT_NOT_FOUND);
                    assertThat(action.getMessage()).contains("engine-rest base URL");
                });
    }

    @Test
    void notFoundWithResourceMissingBodyIsClassifiedAsResourceMissing() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/external-task/missing/lock", exchange -> {
            byte[] body = ("{\"type\":\"RestException\",\"message\":\"External task with id missing does not exist\","
                    + "\"code\":0}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        client = newClient(baseUri(server), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.postNoContent("/external-task/missing/lock", Map.of()))
                .isInstanceOf(EngineActionException.class)
                .satisfies(e -> assertThat(CamundaFailureClassifier.classify((EngineActionException) e))
                        .isEqualTo(CamundaFailureClassification.RESOURCE_MISSING));
    }

    @Test
    void unparseableErrorBodyStillProducesAUsefulException() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/broken", exchange -> {
            byte[] body = "not json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        client = newClient(baseUri(server), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.getJson("/broken", Object.class))
                .isInstanceOf(EngineActionException.class)
                .satisfies(e -> {
                    EngineActionException action = (EngineActionException) e;
                    assertThat(action.status()).isEqualTo(500);
                    assertThat(action.getMessage()).contains("not json");
                });
    }

    @Test
    void emptyErrorBodyStillProducesAUsefulException() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/empty", exchange -> exchange.sendResponseHeaders(500, -1));
        server.start();

        client = newClient(baseUri(server), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.getJson("/empty", Object.class))
                .isInstanceOf(EngineActionException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("empty"));
    }

    @Test
    void successfulNoContentResponseReturnsNormally() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> exchange.sendResponseHeaders(204, -1));
        server.start();

        client = newClient(baseUri(server), Duration.ofSeconds(2));

        client.postNoContent("/ok", Map.of("workerId", "w1"));
    }

    private static URI baseUri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static EngineRestClient newClient(URI baseUri, Duration requestTimeout) {
        return new EngineRestClient(new EngineConfig(
                baseUri, requestTimeout, Duration.ofSeconds(30), "test-worker", 50, Map.of()));
    }

    private static int findFreePortWithNoListener() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
