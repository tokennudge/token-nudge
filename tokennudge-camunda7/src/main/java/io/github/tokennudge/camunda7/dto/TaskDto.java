package io.github.tokennudge.camunda7.dto;

/**
 * A user task as returned by {@code POST /task} (query).
 *
 * <p>Unlike {@link ExternalTaskDto}, the engine's task resource does not include the owning
 * process instance's business key or process definition key directly; callers resolve those
 * via the process instance/definition endpoints.
 *
 * @param id                  the task id
 * @param name                the task name
 * @param taskDefinitionKey   the BPMN user task activity id
 * @param processInstanceId   the owning process instance id, or {@code null} for a
 *                            standalone (non-process) task
 * @param executionId         the execution id the task is attached to, or {@code null}
 * @param processDefinitionId the process definition id, or {@code null}
 * @param assignee            the assignee, or {@code null} if unassigned
 * @param owner               the owner, or {@code null}
 * @param created             when the task was created, ISO-8601
 * @param due                 the due date, ISO-8601, or {@code null}
 * @param priority            the task's priority
 * @param suspended           whether the owning process instance is suspended
 * @param tenantId            the tenant id, or {@code null}
 * @param formKey             the form key, or {@code null}
 * @param description         the task description, or {@code null}
 */
public record TaskDto(
        String id,
        String name,
        String taskDefinitionKey,
        String processInstanceId,
        String executionId,
        String processDefinitionId,
        String assignee,
        String owner,
        String created,
        String due,
        Integer priority,
        Boolean suspended,
        String tenantId,
        String formKey,
        String description) {
}
