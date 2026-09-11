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
 * exception's message, and, when available (for example a parsed Camunda engine-rest error
 * body), via {@link #status()}, {@link #engineErrorType()} and {@link #engineMessage()}.
 */
public class EngineActionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String engineErrorType;
    private final String engineMessage;

    /**
     * Creates a new exception with the given message. {@link #status()} is {@code -1} and
     * {@link #engineErrorType()}/{@link #engineMessage()} are {@code null}.
     *
     * @param message the error message
     */
    public EngineActionException(String message) {
        this(message, -1, null, null);
    }

    /**
     * Creates a new exception with the given message and cause. {@link #status()} is
     * {@code -1} and {@link #engineErrorType()}/{@link #engineMessage()} are {@code null}.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public EngineActionException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
        this.engineErrorType = null;
        this.engineMessage = null;
    }

    /**
     * Creates a new exception carrying the structured details of a rejected request, for
     * example a parsed Camunda engine-rest error body ({@code {"type", "message", "code"}})
     * together with the HTTP status of the response.
     *
     * @param message         the error message (typically combining the details below into
     *                        one human-readable string)
     * @param status          the HTTP status code of the rejected response, or {@code -1} if
     *                        not applicable/unknown
     * @param engineErrorType the engine's own error type/class (for example
     *                        {@code "RestException"}), or {@code null} if not available
     * @param engineMessage   the engine's own error message, or {@code null} if not
     *                        available
     */
    public EngineActionException(String message, int status, String engineErrorType, String engineMessage) {
        super(message);
        this.status = status;
        this.engineErrorType = engineErrorType;
        this.engineMessage = engineMessage;
    }

    /**
     * Returns the HTTP status code of the response that rejected the request.
     *
     * @return the HTTP status code, or {@code -1} if not applicable/unknown
     */
    public int status() {
        return status;
    }

    /**
     * Returns the engine's own error type/class (for example {@code "RestException"} or
     * {@code "NotFoundException"}), as reported in the rejected response's error body.
     *
     * @return the engine error type, or {@code null} if not available
     */
    public String engineErrorType() {
        return engineErrorType;
    }

    /**
     * Returns the engine's own error message, as reported in the rejected response's error
     * body, distinct from {@link #getMessage()} (which may combine this with other
     * details).
     *
     * @return the engine error message, or {@code null} if not available
     */
    public String engineMessage() {
        return engineMessage;
    }
}
