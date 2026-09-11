package io.github.tokennudge.camunda7.dto;

import java.util.Map;

/**
 * The request body for {@code POST /task/{id}/complete}.
 *
 * <p>Unlike {@link CompleteExternalTaskRequest}, there is no {@code workerId}: user tasks are
 * not lock-based, so completion is not restricted to whichever caller last "claimed" it (see
 * {@code io.github.tokennudge.camunda7.Camunda7EngineAdapter#claim}).
 *
 * @param variables the process variables to set, keyed by name; may be {@code null} or empty
 */
public record CompleteTaskRequest(Map<String, TypedValueDto> variables) {
}
