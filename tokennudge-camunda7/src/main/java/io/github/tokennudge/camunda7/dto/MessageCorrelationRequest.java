package io.github.tokennudge.camunda7.dto;

import java.util.Map;

/**
 * The request body for {@code POST /message}, correlating a message to a waiting process
 * instance either by id or by business key.
 *
 * @param messageName       the message name
 * @param processInstanceId the target process instance id, or {@code null} if correlating
 *                          by {@code businessKey} instead
 * @param businessKey       the target process instance's business key, or {@code null} if
 *                          correlating by {@code processInstanceId} instead
 * @param processVariables  the process variables to set, keyed by name; may be {@code null}
 *                          or empty
 * @param resultEnabled     {@code true} to have the engine report which process instances
 *                          were correlated (used to detect ambiguous correlations)
 */
public record MessageCorrelationRequest(
        String messageName,
        String processInstanceId,
        String businessKey,
        Map<String, TypedValueDto> processVariables,
        boolean resultEnabled) {
}
