package io.github.tokennudge.camunda7.dto;

/**
 * An external task as returned by {@code POST /external-task} (query) and by the lock/fetch
 * endpoints.
 *
 * @param id                    the external task id
 * @param topicName             the topic the task is waiting on
 * @param workerId              the worker currently holding the lock, or {@code null} if
 *                              unlocked
 * @param lockExpirationTime    when the current lock expires, ISO-8601, or {@code null}
 * @param processInstanceId     the owning process instance id
 * @param executionId           the execution id the task is attached to
 * @param processDefinitionId   the process definition id (deployment-specific)
 * @param processDefinitionKey  the process definition key
 * @param activityId            the BPMN activity id of the external task
 * @param activityInstanceId    the activity instance id
 * @param tenantId              the tenant id, or {@code null}
 * @param retries               the remaining retries, or {@code null} if never failed
 * @param errorMessage          the last failure's error message, or {@code null}
 * @param suspended             whether the owning process instance is suspended
 * @param priority              the task's priority
 * @param businessKey           the owning process instance's business key, or {@code null}
 */
public record ExternalTaskDto(
        String id,
        String topicName,
        String workerId,
        String lockExpirationTime,
        String processInstanceId,
        String executionId,
        String processDefinitionId,
        String processDefinitionKey,
        String activityId,
        String activityInstanceId,
        String tenantId,
        Integer retries,
        String errorMessage,
        Boolean suspended,
        Integer priority,
        String businessKey) {
}
