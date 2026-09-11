package io.github.tokennudge.camunda7.dto;

import java.util.Map;

/**
 * A variable instance as returned by {@code GET /variable-instance}, used to fetch the
 * variables visible at a wait state. Unlike {@link TypedValueDto}, the type/value/valueInfo
 * triple is flattened directly onto this resource rather than nested.
 *
 * @param id                   the variable instance id
 * @param name                 the variable name
 * @param type                 the engine's type name, see {@link TypedValueDto#type()}
 * @param value                the raw JSON value, see {@link TypedValueDto#value()}
 * @param valueInfo            additional type-specific metadata, see
 *                             {@link TypedValueDto#valueInfo()}
 * @param processDefinitionId  the owning process definition id
 * @param processInstanceId    the owning process instance id
 * @param executionId          the execution id this variable is visible from (the process
 *                             instance id for process-scoped variables, or a narrower
 *                             execution id for execution-local variables)
 * @param activityInstanceId   the activity instance id the variable was set in, or
 *                             {@code null}
 * @param tenantId             the tenant id, or {@code null}
 * @param errorMessage         set instead of {@code value} if the variable could not be
 *                             deserialized by the engine, or {@code null}
 */
public record VariableInstanceDto(
        String id,
        String name,
        String type,
        Object value,
        Map<String, Object> valueInfo,
        String processDefinitionId,
        String processInstanceId,
        String executionId,
        String activityInstanceId,
        String tenantId,
        String errorMessage) {
}
