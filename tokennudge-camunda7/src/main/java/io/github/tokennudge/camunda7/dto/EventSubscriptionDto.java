package io.github.tokennudge.camunda7.dto;

/**
 * An event subscription as returned by {@code GET /event-subscription}, used to discover
 * message wait states (intermediate catch events, receive tasks).
 *
 * @param id                the subscription id
 * @param eventType         the subscription type, for example {@code "message"}
 * @param eventName         the message name the subscription is waiting for
 * @param executionId       the execution id the subscription is attached to
 * @param processInstanceId the owning process instance id, or {@code null} for a message
 *                          start event subscription
 * @param activityId        the BPMN activity id, or {@code null}
 * @param tenantId          the tenant id, or {@code null}
 * @param createdDate       when the subscription was created, ISO-8601
 */
public record EventSubscriptionDto(
        String id,
        String eventType,
        String eventName,
        String executionId,
        String processInstanceId,
        String activityId,
        String tenantId,
        String createdDate) {
}
