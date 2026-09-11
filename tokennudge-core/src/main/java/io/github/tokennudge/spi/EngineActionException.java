package io.github.tokennudge.spi;

import java.io.Serial;

/**
 * Thrown by an {@link EngineAdapter} when the engine was reached but rejected a specific
 * {@link EngineAdapter#execute(io.github.tokennudge.model.WaitState, io.github.tokennudge.model.Action)}
 * or {@link EngineAdapter#claim(io.github.tokennudge.model.WaitState)} request (for
 * example, the engine returned a client error response for a completion attempt).
 *
 * <p>Contrast with {@link EngineAccessException}, which signals that the engine could not
 * be reached at all. During the loop, an action that fails with this exception is recorded
 * as {@link io.github.tokennudge.model.Outcome#ACTION_FAILED} with this exception's
 * message; it never stops the loop. Adapters are encouraged to include any
 * engine-specific status/type/message details from the rejected request in this
 * exception's message.
 */
public class EngineActionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the given message.
     *
     * @param message the error message
     */
    public EngineActionException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public EngineActionException(String message, Throwable cause) {
        super(message, cause);
    }
}
