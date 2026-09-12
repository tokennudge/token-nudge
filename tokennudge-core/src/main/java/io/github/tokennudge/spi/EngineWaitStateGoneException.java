package io.github.tokennudge.spi;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.WaitState;

import java.io.Serial;

/**
 * Thrown by an {@link EngineAdapter} from
 * {@link EngineAdapter#execute(WaitState, Action)} when the engine authoritatively reports
 * that the targeted wait state no longer exists &mdash; for example, a 404 response meaning
 * the user task or external task was already completed, or the process instance it belonged
 * to has already ended.
 *
 * <p>This is a benign race, not a failure: another worker, a human, or the process itself
 * already handled the wait state between this worker's discovery and its attempt to act.
 * Contrast with {@link EngineActionException}, which signals that the engine rejected the
 * request for some other, definite reason (for example, invalid variables), and remains an
 * {@link io.github.tokennudge.model.Outcome#ACTION_FAILED}. During the loop, an
 * {@code execute} call that fails with this exception is instead recorded as
 * {@link io.github.tokennudge.model.Outcome#CLAIM_LOST} and never retried, exactly as
 * {@link ClaimResult#LOST} is for {@link EngineAdapter#claim(WaitState)}.
 *
 * <p>Adapters should only throw this when the engine's own response makes the "already
 * gone" fact unambiguous; anything less certain should be reported as
 * {@link EngineActionException} (a definite rejection) or left as a plain
 * {@link RuntimeException} (an ambiguous, unknown outcome), per {@link EngineAdapter}'s class
 * Javadoc "Failure contract" section.
 */
public class EngineWaitStateGoneException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the given message.
     *
     * @param message the error message
     */
    public EngineWaitStateGoneException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public EngineWaitStateGoneException(String message, Throwable cause) {
        super(message, cause);
    }
}
