package io.github.tokennudge.model;

import io.github.tokennudge.CorrelationStrategy;
import io.github.tokennudge.Variables;

import java.util.Objects;

/**
 * Correlates a message to the matched wait state's process instance, targeted either by
 * process instance id or by business key (see {@link CorrelationStrategy}), submitting the
 * given process variables.
 *
 * @param strategy  how to target the process instance to correlate to, never {@code null}
 * @param variables the process variables to submit; use {@link Variables#empty()} for none,
 *                  never {@code null}
 */
public record CorrelateMessage(CorrelationStrategy strategy, Variables variables) implements Action {

    /**
     * Validates required fields.
     *
     * @throws NullPointerException if {@code strategy} or {@code variables} is {@code null}
     */
    public CorrelateMessage {
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(variables, "variables must not be null");
    }
}
