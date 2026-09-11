package io.github.tokennudge.camunda7.dto;

/**
 * The request body for {@code POST /external-task/{id}/lock}.
 *
 * @param workerId      the worker claiming the lock
 * @param lockDuration the lock duration in milliseconds
 */
public record LockExternalTaskRequest(String workerId, long lockDuration) {
}
