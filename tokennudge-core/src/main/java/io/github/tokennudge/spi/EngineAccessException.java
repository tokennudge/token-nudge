package io.github.tokennudge.spi;

import java.io.Serial;

/**
 * Thrown by an {@link EngineAdapter} when it cannot reach or communicate with the engine at
 * all: connectivity failures, transport-level errors, timeouts, or discovery failures.
 *
 * <p>Contrast with {@link EngineActionException}, which signals that the engine was
 * reached but rejected a specific request. During the loop's discovery phase, this
 * exception is logged at {@code WARNING} and the affected group is skipped for that
 * iteration; it never stops the loop.
 *
 * <p>When thrown from {@link EngineAdapter#claim(io.github.tokennudge.model.WaitState)} or
 * {@link EngineAdapter#execute(io.github.tokennudge.model.WaitState,
 * io.github.tokennudge.model.Action)} specifically, this exception must mean the request
 * was definitely not delivered to the engine at all (for example, the connection could not
 * even be established); the loop retries such a failure on the next iteration. Any other
 * {@link RuntimeException} from those two methods is treated as an ambiguous, unknown
 * outcome and is never retried &mdash; see {@link EngineAdapter}'s class Javadoc "Failure
 * contract" section for the full rules.
 */
public class EngineAccessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the given message.
     *
     * @param message the error message
     */
    public EngineAccessException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause, for example an {@link java.io.IOException}
     */
    public EngineAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
