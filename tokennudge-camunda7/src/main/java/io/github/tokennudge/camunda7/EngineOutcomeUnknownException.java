package io.github.tokennudge.camunda7;

import java.io.Serial;

/**
 * Thrown by {@link EngineRestClient} when a request may or may not have reached the engine
 * and been applied: the connection was established, but the response could not be obtained
 * (a request timeout, a dropped connection, or the calling thread being interrupted while
 * waiting for the response).
 *
 * <p>This is deliberately distinct from
 * {@link io.github.tokennudge.spi.EngineAccessException}, which means "definitely not
 * delivered", per the adapter failure contract documented on
 * {@link io.github.tokennudge.spi.EngineAdapter}. Callers (the future {@code
 * Camunda7EngineAdapter}) must never treat an instance of this exception as safe to retry.
 */
class EngineOutcomeUnknownException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    EngineOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
