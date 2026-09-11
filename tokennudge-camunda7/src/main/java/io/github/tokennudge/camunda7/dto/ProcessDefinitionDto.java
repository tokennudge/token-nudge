package io.github.tokennudge.camunda7.dto;

/**
 * A process definition as returned by {@code GET /process-definition/{id}}, used to resolve
 * a deployment-specific {@code definitionId} (as seen on a process instance) to its stable
 * {@code key}.
 *
 * @param id           the process definition id (deployment-specific)
 * @param key          the process definition key, stable across deployments
 * @param category     the BPMN {@code targetNamespace}, or {@code null}
 * @param name         the process name, or {@code null}
 * @param version      the deployment version
 * @param resource     the BPMN resource file name
 * @param deploymentId the deployment id
 * @param tenantId     the tenant id, or {@code null}
 * @param suspended    whether the definition is suspended
 */
public record ProcessDefinitionDto(
        String id,
        String key,
        String category,
        String name,
        Integer version,
        String resource,
        String deploymentId,
        String tenantId,
        Boolean suspended) {
}
