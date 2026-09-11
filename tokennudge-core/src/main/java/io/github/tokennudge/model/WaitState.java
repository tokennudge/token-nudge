package io.github.tokennudge.model;

import java.util.Objects;

/**
 * A single wait state observed in a running process instance: an external task, a user
 * task, or a message subscription.
 *
 * @param kind                  the kind of wait state
 * @param id                    the engine-specific identifier of the wait state (external
 *                              task id, task id, or event subscription id), never
 *                              {@code null}
 * @param name                  the topic name, task definition key, or message name,
 *                              depending on {@code kind}; never {@code null}
 * @param processInstanceId     the id of the owning process instance, never {@code null}
 * @param processDefinitionKey  the key of the process definition, never {@code null}
 * @param activityId            the BPMN activity id, or {@code null} if not known
 * @param businessKey           the business key of the process instance, or {@code null}
 *                              if none was set
 * @param executionId           the id of the execution the wait state belongs to, never
 *                              {@code null}
 * @param tenantId              the tenant id, or {@code null} if none
 */
public record WaitState(
        WaitStateKind kind,
        String id,
        String name,
        String processInstanceId,
        String processDefinitionKey,
        String activityId,
        String businessKey,
        String executionId,
        String tenantId) {

    /**
     * Validates required fields.
     *
     * @throws NullPointerException if {@code kind}, {@code id}, {@code name},
     *                              {@code processInstanceId}, {@code processDefinitionKey},
     *                              or {@code executionId} is {@code null}
     */
    public WaitState {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(processInstanceId, "processInstanceId must not be null");
        Objects.requireNonNull(processDefinitionKey, "processDefinitionKey must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
    }
}
