package io.github.tokennudge.camunda7.dto;

/**
 * A process instance as returned by {@code POST /process-instance} (query), used to resolve
 * a wait state's business key and process definition.
 *
 * @param id           the process instance id
 * @param definitionId the process definition id (deployment-specific); resolve to a
 *                     process definition key via {@code GET /process-definition/{id}}
 * @param businessKey  the business key, or {@code null} if none was set
 * @param ended        whether the process instance has ended
 * @param suspended    whether the process instance is suspended
 * @param tenantId     the tenant id, or {@code null}
 */
public record ProcessInstanceDto(
        String id,
        String definitionId,
        String businessKey,
        Boolean ended,
        Boolean suspended,
        String tenantId) {
}
