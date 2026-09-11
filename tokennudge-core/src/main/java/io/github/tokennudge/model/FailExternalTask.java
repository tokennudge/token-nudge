package io.github.tokennudge.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Fails an external task with a technical error message, optionally leaving retries so the
 * engine schedules a retry, or exhausting them (retries {@code == 0}) to raise an incident.
 *
 * <p><strong>{@code retries > 0} combined with {@code retryTimeout} of {@link Duration#ZERO}:</strong>
 * the task becomes fetchable again immediately. The current run does not re-handle it, since
 * its id is already recorded as handled for this run, but a later {@code reset()} clears that
 * memory, so the same task can be discovered and handled again in a subsequent run.
 *
 * @param errorMessage the failure message recorded on the external task / incident, never
 *                     {@code null}
 * @param retries      the number of retries left after this failure; {@code 0} creates an
 *                     incident, must not be negative
 * @param retryTimeout the delay before the task becomes available again for retry, never
 *                     {@code null}, must not be negative
 */
public record FailExternalTask(String errorMessage, int retries, Duration retryTimeout) implements Action {

    /**
     * Validates required fields and numeric ranges.
     *
     * @throws NullPointerException     if {@code errorMessage} or {@code retryTimeout} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code retries} is negative, or
     *                                  {@code retryTimeout} is negative
     */
    public FailExternalTask {
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        Objects.requireNonNull(retryTimeout, "retryTimeout must not be null");
        if (retries < 0) {
            throw new IllegalArgumentException("retries must not be negative: " + retries);
        }
        if (retryTimeout.isNegative()) {
            throw new IllegalArgumentException("retryTimeout must not be negative: " + retryTimeout);
        }
    }
}
