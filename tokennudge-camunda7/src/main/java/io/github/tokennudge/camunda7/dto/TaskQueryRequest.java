package io.github.tokennudge.camunda7.dto;

import java.util.List;

/**
 * The request body for {@code POST /task} (query): active user tasks whose task definition
 * key is one of the given ones.
 *
 * @param taskDefinitionKeyIn the task definition keys to match, one per registered rule
 * @param active              {@code true} to return only tasks belonging to a running (not
 *                            suspended) process instance
 */
public record TaskQueryRequest(List<String> taskDefinitionKeyIn, boolean active) {
}
