package io.github.tokennudge.camunda7;

import io.github.tokennudge.Variables;
import io.github.tokennudge.camunda7.dto.BpmnErrorRequest;
import io.github.tokennudge.camunda7.dto.CompleteExternalTaskRequest;
import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.camunda7.dto.ExternalTaskQueryRequest;
import io.github.tokennudge.camunda7.dto.FailureRequest;
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
 * <p>As of this iteration (6b/7), the external-task path is fully implemented: completion,
 * {@link ThrowBpmnError}, and {@link FailExternalTask}. {@link WaitStateKind#USER_TASK}/
 * {@link WaitStateKind#MESSAGE_SUBSCRIPTION} discovery still returns an empty list
 * (iterations 9 and 10); {@link #execute(WaitState, io.github.tokennudge.model.Action)}
 * explicitly guards against being called with a non-{@link WaitStateKind#EXTERNAL_TASK}
 * wait state (which cannot happen yet via this adapter's own {@link #discover} results, but
 * could via a future kind or a test-only wrapper) and rejects it with a clear
 * {@link EngineActionException} rather than silently ignoring it.
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
     *
     * <p><strong>Known side effect of an ambiguous claim timeout:</strong> if
     * {@code POST /external-task/{id}/lock} times out on the client side but actually
     * succeeded on the engine, this worker holds the lock even though the loop journals
     * {@code ACTION_FAILED} and never retries it, so the task appears stuck (untouched by
     * this run) until {@link EngineConfig#lockDuration()} expires and another worker can
     * claim it again.
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
     * Executes an action against a claimed external task: {@link CompleteExternalTask},
     * {@link ThrowBpmnError}, or {@link FailExternalTask} &mdash; the only {@link Action}
     * subtypes defined so far ({@code Action} is sealed; user-task and message actions are
     * added, and this {@code switch} extended, in iterations 9/10). Whichever action-specific
     * case is added next must keep failing with a clear, definite
     * {@link EngineActionException} for anything genuinely not applicable to an external
     * task, rather than silently ignoring it.
     *
     * <p>A failure to build the request body itself &mdash; for example
     * {@link VariableCodec#encode(Object)} rejecting an unsupported variable value, or
     * {@link FailExternalTask#retryTimeout()} overflowing {@link java.time.Duration#toMillis()}
     * &mdash; happens before any HTTP call is made and is wrapped here as a definite
     * {@link EngineActionException} (every {@code build*Request} helper below catches any
     * {@link RuntimeException} raised while assembling the request, not only
     * {@link IllegalArgumentException}), never left as an ambiguous outcome: nothing was ever
     * sent to the engine, so the loop must not journal it as "outcome unknown".
     *
     * @throws EngineActionException if {@code waitState} is not a
     *                                {@link WaitStateKind#EXTERNAL_TASK}: no action defined so
     *                                far applies to any other kind
     */
    @Override
    public void execute(WaitState waitState, Action action) {
        if (waitState.kind() != WaitStateKind.EXTERNAL_TASK) {
            throw new EngineActionException(
                    "cannot execute " + action.getClass().getSimpleName() + " against a " + waitState.kind()
                            + " wait state (" + waitState.id() + "): only EXTERNAL_TASK is supported");
        }
        switch (action) {
            case CompleteExternalTask complete -> completeExternalTask(waitState, complete);
            case ThrowBpmnError bpmnError -> throwBpmnError(waitState, bpmnError);
            case FailExternalTask failure -> failExternalTask(waitState, failure);
        }
    }

    private void completeExternalTask(WaitState waitState, CompleteExternalTask complete) {
        CompleteExternalTaskRequest request = buildCompleteRequest(waitState, config.workerId(), complete);
        client.postNoContent("/external-task/" + waitState.id() + "/complete", request);
    }

    static CompleteExternalTaskRequest buildCompleteRequest(
            WaitState waitState, String workerId, CompleteExternalTask complete) {
        try {
            return new CompleteExternalTaskRequest(workerId, encodeVariables(complete.variables()));
        } catch (RuntimeException e) {
            throw encodingFailure("CompleteExternalTask", waitState, e);
        }
    }

    /**
     * Fails an external task with a BPMN error via {@code POST /external-task/{id}/bpmnError},
     * to be caught by a matching boundary or intermediate event. If the process definition has
     * no such matching event, the engine has been observed (Camunda 7.24 and CIB Seven 2.2.0)
     * to simply end the wait state's scope rather than reject the request, so this call can
     * still return normally in that case &mdash; it is not a reliable way to trigger a
     * rejection.
     */
    private void throwBpmnError(WaitState waitState, ThrowBpmnError bpmnError) {
        BpmnErrorRequest request = buildBpmnErrorRequest(waitState, config.workerId(), bpmnError);
        client.postNoContent("/external-task/" + waitState.id() + "/bpmnError", request);
    }

    static BpmnErrorRequest buildBpmnErrorRequest(WaitState waitState, String workerId, ThrowBpmnError bpmnError) {
        try {
            return new BpmnErrorRequest(
                    workerId, bpmnError.errorCode(), bpmnError.errorMessage(), encodeVariables(bpmnError.variables()));
        } catch (RuntimeException e) {
            throw encodingFailure("ThrowBpmnError", waitState, e);
        }
    }

    /**
     * Fails an external task technically via {@code POST /external-task/{id}/failure}.
     * {@link FailExternalTask#retryTimeout()} is converted from a {@link java.time.Duration}
     * to milliseconds, as required by the engine-rest request body; {@code retries == 0}
     * makes the engine raise an incident immediately.
     */
    private void failExternalTask(WaitState waitState, FailExternalTask failure) {
        FailureRequest request = buildFailureRequest(waitState, config.workerId(), failure);
        client.postNoContent("/external-task/" + waitState.id() + "/failure", request);
    }

    static FailureRequest buildFailureRequest(WaitState waitState, String workerId, FailExternalTask failure) {
        try {
            return new FailureRequest(
                    workerId, failure.errorMessage(), failure.retries(), failure.retryTimeout().toMillis());
        } catch (RuntimeException e) {
            // Variables.of(...) rejects unsupported types with IllegalArgumentException before this
            // point; Duration.toMillis() can also throw ArithmeticException on overflow (for example
            // Duration.ofSeconds(Long.MAX_VALUE / 500)). Either way, nothing has been sent yet.
            throw encodingFailure("FailExternalTask", waitState, e);
        }
    }

    private static Map<String, TypedValueDto> encodeVariables(Variables variables) {
        Map<String, TypedValueDto> encoded = new LinkedHashMap<>();
        variables.asMap().forEach((name, value) -> encoded.put(name, VariableCodec.encode(value)));
        return encoded;
    }

    private static EngineActionException encodingFailure(
            String actionName, WaitState waitState, RuntimeException cause) {
        return new EngineActionException(
                "could not encode " + actionName + " request for external task " + waitState.id()
                        + " before sending it to the engine: " + cause.getMessage(), cause);
    }

    @Override
    public void close() {
        client.close();
    }
}
