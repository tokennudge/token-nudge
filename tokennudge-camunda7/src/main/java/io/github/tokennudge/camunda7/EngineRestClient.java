package io.github.tokennudge.camunda7;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.tokennudge.camunda7.dto.ErrorDto;
import io.github.tokennudge.spi.EngineAccessException;
import io.github.tokennudge.spi.EngineActionException;
import io.github.tokennudge.spi.EngineConfig;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * A thin engine-rest client wrapping {@link HttpClient}, applying {@link EngineConfig}'s
 * headers and request timeout and mapping failures to the SPI's exception contract.
 *
 * <p>Exception mapping (see {@code io.github.tokennudge.spi.EngineAdapter}'s "adapter failure
 * contract" and "The JDK {@code HttpClient} trap" Javadoc sections):
 * <ul>
 *   <li>A connect-phase failure ({@link ConnectException},
 *       {@link HttpConnectTimeoutException}, or anything else thrown before the request was
 *       sent) becomes {@link EngineAccessException} ("not delivered").</li>
 *   <li>{@link HttpTimeoutException} thrown after connecting, or any other {@link IOException}
 *       from {@link HttpClient#send}, becomes {@link EngineOutcomeUnknownException}
 *       ("ambiguous outcome"), never {@link EngineAccessException}.</li>
 *   <li>An interruption while waiting for the response restores the interrupt flag and also
 *       becomes {@link EngineOutcomeUnknownException}, since the request may already have
 *       been sent.</li>
 *   <li>A non-2xx response with a parseable Camunda error body becomes
 *       {@link EngineActionException}, carrying the HTTP status and the body's
 *       {@code type}/{@code message}. An unparseable or empty error body still becomes an
 *       {@link EngineActionException}, with a message describing the raw body.</li>
 * </ul>
 */
final class EngineRestClient implements AutoCloseable {

    private static final Logger LOG = System.getLogger(EngineRestClient.class.getName());

    private final EngineConfig config;
    private final HttpClient httpClient;

    EngineRestClient(EngineConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .build();
    }

    /**
     * Sends a {@code GET} request and parses the JSON response body.
     *
     * @param path         the request path, relative to {@link EngineConfig#baseUri()},
     *                     starting with {@code /}
     * @param responseType the response body's type
     * @param <T>          the response body's type
     * @return the parsed response body
     */
    <T> T getJson(String path, Class<T> responseType) {
        HttpRequest request = requestBuilder(path).GET().build();
        return parseSuccess(send(request), responseType);
    }

    /**
     * Sends a {@code POST} request with a JSON body and parses the JSON response body.
     *
     * @param path         the request path, relative to {@link EngineConfig#baseUri()},
     *                     starting with {@code /}
     * @param requestBody  the request body, serialized as JSON
     * @param responseType the response body's type
     * @param <T>          the response body's type
     * @return the parsed response body
     */
    <T> T postJson(String path, Object requestBody, Class<T> responseType) {
        HttpRequest request = requestBuilder(path).POST(jsonBody(requestBody)).build();
        return parseSuccess(send(request), responseType);
    }

    /**
     * Sends a {@code POST} request with a JSON body, expecting no response content (a
     * {@code 204}-style endpoint).
     *
     * @param path        the request path, relative to {@link EngineConfig#baseUri()},
     *                    starting with {@code /}
     * @param requestBody the request body, serialized as JSON; may be {@code null} for an
     *                    empty body
     */
    void postNoContent(String path, Object requestBody) {
        HttpRequest request = requestBuilder(path).POST(jsonBody(requestBody)).build();
        checkSuccess(send(request));
    }

    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        config.headers().forEach(builder::header);
        return builder;
    }

    private URI resolve(String path) {
        return URI.create(config.baseUri().toString() + path);
    }

    private HttpRequest.BodyPublisher jsonBody(Object requestBody) {
        if (requestBody == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        try {
            byte[] json = JsonSupport.MAPPER.writeValueAsBytes(requestBody);
            return HttpRequest.BodyPublishers.ofByteArray(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("could not encode request body as JSON: " + requestBody, e);
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            // Subclass of HttpTimeoutException: must be checked before it.
            throw new EngineAccessException("connecting to the engine timed out: " + request.uri(), e);
        } catch (ConnectException e) {
            throw new EngineAccessException("could not connect to the engine at " + request.uri(), e);
        } catch (HttpTimeoutException e) {
            throw new EngineOutcomeUnknownException(
                    "the engine did not respond within the request timeout for " + request.uri()
                            + "; the request may or may not have been applied", e);
        } catch (IOException e) {
            throw new EngineOutcomeUnknownException(
                    "communication with the engine failed for " + request.uri()
                            + "; the request may or may not have been applied", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineOutcomeUnknownException(
                    "interrupted while waiting for the engine's response to " + request.uri()
                            + "; the request may or may not have been applied", e);
        }
    }

    private void checkSuccess(HttpResponse<byte[]> response) {
        if (!isSuccess(response.statusCode())) {
            throw toActionException(response);
        }
    }

    private <T> T parseSuccess(HttpResponse<byte[]> response, Class<T> responseType) {
        if (!isSuccess(response.statusCode())) {
            throw toActionException(response);
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return JsonSupport.MAPPER.readValue(body, responseType);
        } catch (IOException e) {
            throw new EngineOutcomeUnknownException(
                    "the engine returned a " + response.statusCode() + " response for " + response.uri()
                            + " that could not be parsed as " + responseType.getSimpleName(), e);
        }
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private EngineActionException toActionException(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        ErrorDto error = tryParseError(response.body());
        if (error == null) {
            String raw = rawBody(response.body());
            return new EngineActionException(
                    "engine returned HTTP " + status + " for " + response.uri()
                            + " with an unparseable or empty error body: " + raw,
                    status, null, null);
        }
        CamundaFailureClassification classification =
                CamundaFailureClassifier.classify(status, error.type(), error.message());
        String hint = CamundaFailureClassifier.describe(classification);
        String message = "engine returned HTTP " + status + " (" + error.type() + ") for " + response.uri()
                + ": " + error.message() + (hint == null ? "" : " — " + hint);
        return new EngineActionException(message, status, error.type(), error.message());
    }

    private ErrorDto tryParseError(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return JsonSupport.MAPPER.readValue(body, ErrorDto.class);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "could not parse engine error body", e);
            return null;
        }
    }

    private static String rawBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "(empty)";
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
