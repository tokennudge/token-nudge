package io.github.tokennudge;

import java.io.Serial;

/**
 * Thrown when a {@link Verification} is not satisfied within its timeout (or immediately,
 * if the loop is not running).
 *
 * <p>The message includes the expected vs. actual count, the matching journal entries, and
 * up to 5 "near miss" entries: wait states that matched the verification's selector but did
 * not satisfy its outcome/variable filters.
 *
 * <p>Extends {@link AssertionError} so that verification failures behave like assertion
 * failures in test runners.
 */
public final class VerificationException extends AssertionError {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the given message.
     *
     * @param message the failure message, never {@code null}
     */
    public VerificationException(String message) {
        super(message);
    }
}
