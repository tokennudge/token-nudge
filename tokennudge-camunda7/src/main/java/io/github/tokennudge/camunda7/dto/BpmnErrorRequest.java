package io.github.tokennudge.camunda7.dto;

import java.util.Map;

/**
 * The request body for {@code POST /external-task/{id}/bpmnError}.
 *
 * @param workerId     the worker holding the lock (must match)
 * @param errorCode    the BPMN error code to throw
 * @param errorMessage an error message, or {@code null}
 * @param variables    the process variables to set, keyed by name; may be {@code null} or
 *                     empty
 */
public record BpmnErrorRequest(String workerId, String errorCode, String errorMessage, Map<String, TypedValueDto> variables) {
}
