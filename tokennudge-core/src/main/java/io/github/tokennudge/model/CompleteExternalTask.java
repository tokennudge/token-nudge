package io.github.tokennudge.model;

import io.github.tokennudge.Variables;

import java.util.Objects;

/**
 * Completes an external task successfully, submitting the given output variables.
 *
 * @param variables the output variables to submit; use {@link Variables#empty()} for none,
 *                  never {@code null}
 */
public record CompleteExternalTask(Variables variables) implements Action {

    /**
     * Validates the variables.
     *
     * @throws NullPointerException if {@code variables} is {@code null}
     */
    public CompleteExternalTask {
        Objects.requireNonNull(variables, "variables must not be null");
    }
}
