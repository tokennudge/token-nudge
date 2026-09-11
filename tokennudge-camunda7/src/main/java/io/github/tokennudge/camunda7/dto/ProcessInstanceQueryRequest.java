package io.github.tokennudge.camunda7.dto;

import java.util.List;

/**
 * The request body for {@code POST /process-instance} (query), batched to resolve every
 * process instance discovered in one iteration in a single call.
 *
 * @param processInstanceIds the process instance ids to resolve
 */
public record ProcessInstanceQueryRequest(List<String> processInstanceIds) {
}
