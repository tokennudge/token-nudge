package io.github.tokennudge.model;

import io.github.tokennudge.Variables;

import java.util.Objects;

/**
 * Fails an external task with a BPMN error, to be caught by a boundary or intermediate
 * event with the matching error code.
 *
 * @param errorCode    the BPMN error code, never {@code null}
 * @param errorMessage a human-readable error message, or {@code null} if none
 * @param variables    the output variables to submit alongside the error; use
 *                     {@link Variables#empty()} for none, never {@code null}
 */
public record ThrowBpmnError(String errorCode, String errorMessage, Variables variables) implements Action {

    /**
     * Validates required fields.
     *
     * @throws NullPointerException if {@code errorCode} or {@code variables} is
     *                              {@code null}
     */
    public ThrowBpmnError {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(variables, "variables must not be null");
    }
}
