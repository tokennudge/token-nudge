package io.github.tokennudge.camunda7;

import io.github.tokennudge.camunda7.dto.ProcessDefinitionDto;
import io.github.tokennudge.camunda7.dto.ProcessInstanceDto;
import io.github.tokennudge.camunda7.dto.ProcessInstanceQueryRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a batch of process instance ids to the {@code businessKey}/
 * {@code processDefinitionKey} pair a wait state needs but the {@code /task} and
 * {@code /event-subscription} endpoints do not themselves return (unlike {@code
 * /external-task}, whose DTO already includes both).
 *
 * <p>One {@link #resolve(Set)} call issues a single batched {@code POST /process-instance}
 * for every process instance id passed in (per PLAN.md §2.2), then resolves each distinct
 * {@code definitionId} seen to its stable {@code processDefinitionKey} via
 * {@code GET /process-definition/{id}}, caching that mapping permanently: a deployment's
 * process definition id is immutable for its lifetime, so the same id always resolves to the
 * same key.
 */
final class ProcessInstanceResolver {

    private final EngineRestClient client;
    private final Map<String, String> processDefinitionKeyByDefinitionId = new ConcurrentHashMap<>();

    ProcessInstanceResolver(EngineRestClient client) {
        this.client = client;
    }

    /**
     * Resolves the given process instance ids in a single batched call.
     *
     * @param processInstanceIds the process instance ids to resolve; may be empty
     * @return a map from process instance id to its resolved info, containing an entry for
     *         every id that the engine still recognizes as of this call (an id might be
     *         missing if the process instance ended between discovery and this call)
     */
    Map<String, ProcessInstanceInfo> resolve(Set<String> processInstanceIds) {
        if (processInstanceIds.isEmpty()) {
            return Map.of();
        }
        ProcessInstanceDto[] instances = client.postJson(
                "/process-instance", new ProcessInstanceQueryRequest(List.copyOf(processInstanceIds)),
                ProcessInstanceDto[].class);
        Map<String, ProcessInstanceInfo> resolved = new LinkedHashMap<>();
        for (ProcessInstanceDto instance : instances) {
            String processDefinitionKey = resolveProcessDefinitionKey(instance.definitionId());
            resolved.put(instance.id(), new ProcessInstanceInfo(instance.businessKey(), processDefinitionKey));
        }
        return resolved;
    }

    private String resolveProcessDefinitionKey(String definitionId) {
        if (definitionId == null) {
            return null;
        }
        return processDefinitionKeyByDefinitionId.computeIfAbsent(definitionId, id -> {
            ProcessDefinitionDto definition = client.getJson("/process-definition/" + id, ProcessDefinitionDto.class);
            return definition.key();
        });
    }

    /**
     * The business key and process definition key resolved for one process instance.
     *
     * @param businessKey          the business key, or {@code null} if none was set
     * @param processDefinitionKey the process definition key
     */
    record ProcessInstanceInfo(String businessKey, String processDefinitionKey) {
    }
}
