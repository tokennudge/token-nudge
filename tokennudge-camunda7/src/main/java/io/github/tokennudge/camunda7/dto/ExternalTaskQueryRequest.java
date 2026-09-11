package io.github.tokennudge.camunda7.dto;

/**
 * The request body for {@code POST /external-task} (query): unlocked, active external tasks
 * on a given topic that still have retries left.
 *
 * @param topicName       the topic to query
 * @param notLocked       {@code true} to return only unlocked tasks
 * @param active          {@code true} to return only tasks belonging to a running (not
 *                        suspended) process instance
 * @param withRetriesLeft {@code true} to exclude tasks whose retries have been exhausted
 */
public record ExternalTaskQueryRequest(String topicName, boolean notLocked, boolean active, boolean withRetriesLeft) {
}
