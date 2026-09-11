package io.github.tokennudge.camunda7.dto;

import java.util.Map;

/**
 * The request body for {@code POST /external-task/{id}/complete}.
 *
 * @param workerId  the worker holding the lock (must match)
 * @param variables the process variables to set, keyed by name; may be {@code null} or empty
 */
public record CompleteExternalTaskRequest(String workerId, Map<String, TypedValueDto> variables) {
}
