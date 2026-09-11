package io.github.tokennudge.camunda7.dto;

/**
 * The request body for {@code POST /external-task/{id}/failure}.
 *
 * @param workerId     the worker holding the lock (must match)
 * @param errorMessage the failure's error message
 * @param retries      the remaining retry count to set; {@code 0} creates an incident
 * @param retryTimeout how long, in milliseconds, before the task becomes available again
 */
public record FailureRequest(String workerId, String errorMessage, Integer retries, Long retryTimeout) {
}
