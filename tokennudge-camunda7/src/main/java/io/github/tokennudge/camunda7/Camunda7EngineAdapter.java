package io.github.tokennudge.camunda7;

import io.github.tokennudge.Variables;
import io.github.tokennudge.camunda7.dto.CompleteExternalTaskRequest;
import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.camunda7.dto.ExternalTaskQueryRequest;
import io.github.tokennudge.camunda7.dto.LockExternalTaskRequest;
import io.github.tokennudge.camunda7.dto.TypedValueDto;
import io.github.tokennudge.camunda7.dto.VariableInstanceDto;
import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.FailExternalTask;
import io.github.tokennudge.model.ThrowBpmnError;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.spi.ClaimResult;
import io.github.tokennudge.spi.DiscoveryQuery;
import io.github.tokennudge.spi.EngineAccessException;
import io.github.tokennudge.spi.EngineActionException;
import io.github.tokennudge.spi.EngineAdapter;
import io.github.tokennudge.spi.EngineConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EngineAdapter} implementation talking to a Camunda 7 / CIB Seven engine-rest
 * instance through {@link EngineRestClient}.
 *
 * <p>As of this iteration (6a), only the external-task path is implemented, and only
 * completion; {@link ThrowBpmnError} and {@link FailExternalTask} are rejected with a clear
 * {@link EngineActionException} (iteration 6b), and {@link WaitStateKind#USER_TASK}/
 * {@link WaitStateKind#MESSAGE_SUBSCRIPTION} discovery returns an empty list (iterations 9
 * and 10).
 *
 * <p>Implements the adapter failure contract documented on {@link EngineAdapter}'s class
 * Javadoc: {@link EngineRestClient} already maps every {@code java.net.http.HttpClient}
 * failure to either {@link EngineAccessException} ("not delivered") or
 * {@link EngineOutcomeUnknownException} (ambiguous, left as a plain {@link RuntimeException}
 * for this class to simply not catch); this class only adds the classification of definite
 * {@link EngineActionException} rejections into {@link ClaimResult#LOST} where the plan
 * requires it.
 */
final class Camunda7EngineAdapter implements EngineAdapter {

    private final EngineConfig config;
    private final EngineRestClient client;

    Camunda7EngineAdapter(EngineConfig config) {
        this.config = config;
        this.client = new EngineRestClient(config);
    }

    /**
     * Verifies connectivity with {@code GET /engine}, translating any failure into
     * {@link EngineAccessException} as required by {@link EngineAdapter#checkConnectivity()}.
     *
     * <p>{@code GET /engine} always succeeds (with a {@code 200} listing the deployed
     * engines) against a correctly configured engine-rest base URL, so <em>any</em> rejected
     * or unparseable response here is treated as a configuration problem, not a business-level
     * failure: the message calls out a wrong base URL by name, whether the engine's own JSON
     * body says so explicitly ({@link CamundaFailureClassification#ENDPOINT_NOT_FOUND}, when
     * the wrong path still happens to be routed by the engine-rest application itself) or the
     * request missed the application entirely (an unparseable, non-JSON {@code 404} from the
     * surrounding servlet container, {@link CamundaFailureClassification#UNCLASSIFIED} in that
     * case).
     */
    @Override
    public void checkConnectivity() {
        try {
            client.getJson("/engine", Object[].class);
        } catch (EngineAccessException e) {
            throw e;
        } catch (EngineActionException e) {
            throw new EngineAccessException(
                    "the engine-rest base URL looks wrong (endpoint not found): " + config.baseUri()
                            + " — " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new EngineAccessException(
                    "could not verify connectivity to the engine at " + config.baseUri() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Discovers external tasks per topic via {@code POST /external-task}. User tasks and
     * message subscriptions are not discovered yet (iterations 9 and 10) and return an empty
     * list rather than throwing.
     */
    @Override
    public List<WaitState> discover(DiscoveryQuery query) {
        return switch (query.kind()) {
            case EXTERNAL_TASK -> discoverExternalTasks(query);
            case USER_TASK, MESSAGE_SUBSCRIPTION -> List.of();
        };
    }

    private List<WaitState> discoverExternalTasks(DiscoveryQuery query) {
        List<WaitState> discovered = new ArrayList<>();
        for (String topicName : query.names()) {
            ExternalTaskDto[] tasks = client.postJson(
                    "/external-task?maxResults=" + query.maxResults(),
                    new ExternalTaskQueryRequest(topicName, true, true, true),
                    ExternalTaskDto[].class);
            for (ExternalTaskDto task : tasks) {
                discovered.add(toWaitState(task));
            }
        }
        return discovered;
    }

    static WaitState toWaitState(ExternalTaskDto dto) {
        return new WaitState(
                WaitStateKind.EXTERNAL_TASK,
                dto.id(),
                dto.topicName(),
                dto.processInstanceId(),
                dto.processDefinitionKey(),
                dto.activityId(),
                dto.businessKey(),
                dto.executionId(),
                dto.tenantId());
    }

    /**
     * Claims an external task via {@code POST /external-task/{id}/lock}.
     *
     * <p>A rejection classified {@link CamundaFailureClassification#LOCKED_BY_OTHER_WORKER}
     * or {@link CamundaFailureClassification#RESOURCE_MISSING} becomes {@link ClaimResult#LOST}:
     * the engine authoritatively told us the task is already taken (or gone). Any other
     * {@link EngineActionException} &mdash; in practice
     * {@link CamundaFailureClassification#ENDPOINT_NOT_FOUND} (a misconfigured base URL) or
     * {@link CamundaFailureClassification#UNCLASSIFIED} &mdash; is rethrown unchanged: it is
     * still a definite rejection by the engine, just not one that means "someone else has
     * it", so it must not be reported as the benign {@code CLAIM_LOST}; the loop journals it
     * as {@code ACTION_FAILED} instead. {@link EngineAccessException} (not delivered) and any
     * other {@link RuntimeException} (ambiguous outcome, per the adapter failure contract)
     * are left to propagate unchanged.
     */
    @Override
    public ClaimResult claim(WaitState waitState) {
        LockExternalTaskRequest request =
                new LockExternalTaskRequest(config.workerId(), config.lockDuration().toMillis());
        try {
            client.postNoContent("/external-task/" + waitState.id() + "/lock", request);
            return ClaimResult.CLAIMED;
        } catch (EngineActionException e) {
            if (isLostClaim(CamundaFailureClassifier.classify(e))) {
                return ClaimResult.LOST;
            }
            throw e;
        }
    }

    static boolean isLostClaim(CamundaFailureClassification classification) {
        return classification == CamundaFailureClassification.LOCKED_BY_OTHER_WORKER
                || classification == CamundaFailureClassification.RESOURCE_MISSING;
    }

    /**
     * Returns the variables visible at a wait state via {@code GET /variable-instance},
     * keeping process-scoped entries (the ones visible on the process instance's own root
     * execution) and the wait state's own execution-local entries, with execution-local
     * values overriding process-scoped ones of the same name.
     *
     * <p>This only looks at the process instance and the wait state's own execution: values
     * scoped to an intermediate nested subprocess or multi-instance execution between the
     * process instance and the wait state's execution may be missed (documented risk, see
     * {@code docs/PLAN.md} §6 risk 6).
     */
    @Override
    public Map<String, Object> variables(WaitState waitState) {
        VariableInstanceDto[] instances = client.getJson(
                "/variable-instance?processInstanceIdIn=" + waitState.processInstanceId()
                        + "&deserializeValues=false",
                VariableInstanceDto[].class);
        return filterVariablesForWaitState(waitState, instances);
    }

    static Map<String, Object> filterVariablesForWaitState(WaitState waitState, VariableInstanceDto[] instances) {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (VariableInstanceDto instance : instances) {
            if (waitState.processInstanceId().equals(instance.executionId())) {
                variables.put(instance.name(), VariableCodec.decode(instance.type(), instance.value()));
            }
        }
        for (VariableInstanceDto instance : instances) {
            if (waitState.executionId().equals(instance.executionId())) {
                variables.put(instance.name(), VariableCodec.decode(instance.type(), instance.value()));
            }
        }
        return variables;
    }

    /**
     * Executes an action against a claimed external task. Only {@link CompleteExternalTask}
     * is supported in this iteration; {@link ThrowBpmnError} and {@link FailExternalTask}
     * (iteration 6b) and any future action type throw a clear, definite
     * {@link EngineActionException} so the loop journals {@code ACTION_FAILED} rather than
     * retrying or misreporting the outcome as ambiguous.
     */
    @Override
    public void execute(WaitState waitState, Action action) {
        switch (action) {
            case CompleteExternalTask complete -> completeExternalTask(waitState, complete);
            case ThrowBpmnError ignored -> throw unsupported("ThrowBpmnError", waitState);
            case FailExternalTask ignored -> throw unsupported("FailExternalTask", waitState);
        }
    }

    private void completeExternalTask(WaitState waitState, CompleteExternalTask complete) {
        client.postNoContent(
                "/external-task/" + waitState.id() + "/complete",
                new CompleteExternalTaskRequest(config.workerId(), encodeVariables(complete.variables())));
    }

    private static Map<String, TypedValueDto> encodeVariables(Variables variables) {
        Map<String, TypedValueDto> encoded = new LinkedHashMap<>();
        variables.asMap().forEach((name, value) -> encoded.put(name, VariableCodec.encode(value)));
        return encoded;
    }

    private static EngineActionException unsupported(String actionName, WaitState waitState) {
        return new EngineActionException(
                actionName + " is not supported until iteration 6b: " + waitState.id());
    }

    @Override
    public void close() {
        client.close();
    }
}
