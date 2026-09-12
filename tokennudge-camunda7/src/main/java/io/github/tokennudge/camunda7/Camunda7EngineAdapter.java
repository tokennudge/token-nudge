package io.github.tokennudge.camunda7;

import io.github.tokennudge.CorrelationStrategy;
import io.github.tokennudge.Variables;
import io.github.tokennudge.camunda7.dto.BpmnErrorRequest;
import io.github.tokennudge.camunda7.dto.CompleteExternalTaskRequest;
import io.github.tokennudge.camunda7.dto.CompleteTaskRequest;
import io.github.tokennudge.camunda7.dto.EventSubscriptionDto;
import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.camunda7.dto.ExternalTaskQueryRequest;
import io.github.tokennudge.camunda7.dto.FailureRequest;
import io.github.tokennudge.camunda7.dto.LockExternalTaskRequest;
import io.github.tokennudge.camunda7.dto.MessageCorrelationRequest;
import io.github.tokennudge.camunda7.dto.TaskDto;
import io.github.tokennudge.camunda7.dto.TaskQueryRequest;
import io.github.tokennudge.camunda7.dto.TypedValueDto;
import io.github.tokennudge.camunda7.dto.VariableInstanceDto;
import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.CompleteUserTask;
import io.github.tokennudge.model.CorrelateMessage;
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
import io.github.tokennudge.spi.EngineWaitStateGoneException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link EngineAdapter} implementation talking to a Camunda 7 / CIB Seven engine-rest
 * instance through {@link EngineRestClient}.
 *
 * <p>All three wait-state kinds are implemented: external tasks (completion,
 * {@link ThrowBpmnError}, {@link FailExternalTask}), user tasks ({@link CompleteUserTask}),
 * and message subscriptions ({@link CorrelateMessage}). {@link #execute(WaitState, Action)}
 * guards every case against being called with a mismatched wait-state kind (which cannot
 * happen via this adapter's own {@link #discover} results, but could via a test-only wrapper)
 * and rejects it with a clear {@link EngineActionException} rather than silently ignoring it.
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
    private final ProcessInstanceResolver processInstanceResolver;

    Camunda7EngineAdapter(EngineConfig config) {
        this.config = config;
        this.client = new EngineRestClient(config);
        this.processInstanceResolver = new ProcessInstanceResolver(client);
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
     * Discovers wait states of the given kind: external tasks via {@code POST /external-task}
     * (one call per topic, DTOs already include {@code processDefinitionKey}/
     * {@code businessKey}), user tasks via {@code POST /task} (one batched call for every
     * task definition key), or message subscriptions via {@code GET /event-subscription} (one
     * call per message name). Task and event-subscription DTOs lack
     * {@code processDefinitionKey}/{@code businessKey}, so both are enriched via
     * {@link #processInstanceResolver} with a single batched
     * {@code POST /process-instance} call per {@code discover} invocation.
     */
    @Override
    public List<WaitState> discover(DiscoveryQuery query) {
        return switch (query.kind()) {
            case EXTERNAL_TASK -> discoverExternalTasks(query);
            case USER_TASK -> discoverUserTasks(query);
            case MESSAGE_SUBSCRIPTION -> discoverMessageSubscriptions(query);
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
     * Discovers user tasks via {@code POST /task}. A task with no {@code processInstanceId}
     * (a standalone, non-process task) is not a BPMN wait state and is skipped. Every
     * remaining task's owning process instance is resolved in one batched call via
     * {@link #processInstanceResolver}; a task whose process instance could not be resolved
     * (for example, it ended between this query and the resolve call) is also skipped, since
     * a wait state without a reliable {@code processDefinitionKey}/{@code businessKey} could
     * silently defeat {@code inProcess}/{@code withBusinessKey} filtering.
     */
    private List<WaitState> discoverUserTasks(DiscoveryQuery query) {
        TaskDto[] tasks = client.postJson(
                "/task?maxResults=" + query.maxResults(),
                new TaskQueryRequest(List.copyOf(query.names()), true),
                TaskDto[].class);
        List<TaskDto> attachedToAProcess = new ArrayList<>();
        Set<String> processInstanceIds = new LinkedHashSet<>();
        for (TaskDto task : tasks) {
            if (task.processInstanceId() == null) {
                continue;
            }
            attachedToAProcess.add(task);
            processInstanceIds.add(task.processInstanceId());
        }
        Map<String, ProcessInstanceResolver.ProcessInstanceInfo> resolved =
                processInstanceResolver.resolve(processInstanceIds);
        List<WaitState> discovered = new ArrayList<>();
        for (TaskDto task : attachedToAProcess) {
            ProcessInstanceResolver.ProcessInstanceInfo info = resolved.get(task.processInstanceId());
            if (info != null) {
                discovered.add(toWaitState(task, info));
            }
        }
        return discovered;
    }

    static WaitState toWaitState(TaskDto dto, ProcessInstanceResolver.ProcessInstanceInfo info) {
        return new WaitState(
                WaitStateKind.USER_TASK,
                dto.id(),
                dto.taskDefinitionKey(),
                dto.processInstanceId(),
                info.processDefinitionKey(),
                dto.taskDefinitionKey(),
                info.businessKey(),
                dto.executionId(),
                dto.tenantId());
    }

    /**
     * Discovers message wait states via {@code GET /event-subscription}. A subscription with
     * no {@code processInstanceId} is a message <em>start</em>-event subscription, which has
     * no process instance to correlate to yet; message start events are unsupported (see
     * PLAN.md §6 "Out of v1") and such subscriptions are silently skipped, never correlated.
     * Every remaining subscription's owning process instance is resolved the same way as for
     * user tasks.
     */
    private List<WaitState> discoverMessageSubscriptions(DiscoveryQuery query) {
        List<EventSubscriptionDto> attachedToAProcess = new ArrayList<>();
        Set<String> processInstanceIds = new LinkedHashSet<>();
        for (String messageName : query.names()) {
            EventSubscriptionDto[] subscriptions = client.getJson(
                    "/event-subscription?eventType=message&eventName=" + messageName
                            + "&maxResults=" + query.maxResults(),
                    EventSubscriptionDto[].class);
            for (EventSubscriptionDto subscription : subscriptions) {
                if (subscription.processInstanceId() == null) {
                    continue;
                }
                attachedToAProcess.add(subscription);
                processInstanceIds.add(subscription.processInstanceId());
            }
        }
        Map<String, ProcessInstanceResolver.ProcessInstanceInfo> resolved =
                processInstanceResolver.resolve(processInstanceIds);
        List<WaitState> discovered = new ArrayList<>();
        for (EventSubscriptionDto subscription : attachedToAProcess) {
            ProcessInstanceResolver.ProcessInstanceInfo info = resolved.get(subscription.processInstanceId());
            if (info != null) {
                discovered.add(toWaitState(subscription, info));
            }
        }
        return discovered;
    }

    static WaitState toWaitState(EventSubscriptionDto dto, ProcessInstanceResolver.ProcessInstanceInfo info) {
        return new WaitState(
                WaitStateKind.MESSAGE_SUBSCRIPTION,
                dto.id(),
                dto.eventName(),
                dto.processInstanceId(),
                info.processDefinitionKey(),
                dto.activityId(),
                info.businessKey(),
                dto.executionId(),
                dto.tenantId());
    }

    /**
     * Claims a wait state. For an external task, via {@code POST /external-task/{id}/lock}:
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
     *
     * <p>For a user task or message subscription, this always returns {@link ClaimResult#CLAIMED}
     * without any engine call: engine-rest has no lock/claim endpoint for either kind (a user
     * task's own {@code assignee} field is a business concept, not a worker-exclusion lock,
     * and a message subscription has no lock concept at all), so there is nothing to reserve
     * against a concurrent worker before {@link #execute}. Two workers racing to act on the
     * same user task or message wait state instead both attempt the same completion/
     * correlation request; the first succeeds and the second's request is rejected by the
     * engine itself (an unmatched-task or already-consumed-subscription error), which
     * {@link #execute} reports as {@code ACTION_FAILED} like any other definite rejection.
     */
    @Override
    public ClaimResult claim(WaitState waitState) {
        if (waitState.kind() != WaitStateKind.EXTERNAL_TASK) {
            return ClaimResult.CLAIMED;
        }
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
     * Executes an action against a claimed wait state, dispatching on the action's runtime
     * type: the external-task actions {@link CompleteExternalTask}, {@link ThrowBpmnError},
     * and {@link FailExternalTask}, the user-task action {@link CompleteUserTask}, and the
     * message-correlation action {@link CorrelateMessage}. Every case first checks that
     * {@code waitState}'s kind actually matches the action (for example a
     * {@link CompleteUserTask} against anything but a {@link WaitStateKind#USER_TASK} wait
     * state), rejecting a mismatch with a clear, definite {@link EngineActionException} rather
     * than silently ignoring it or sending a nonsensical request.
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
     * @throws EngineActionException        if {@code waitState}'s kind does not match the
     *                                       action, or (for {@link CorrelateMessage} with
     *                                       {@link CorrelationStrategy.ByBusinessKey}) if the
     *                                       wait state has no business key
     * @throws EngineWaitStateGoneException if a user-task completion or message correlation
     *                                       is rejected because the target already no longer
     *                                       exists (a benign race with another worker, a
     *                                       human, or the process itself)
     */
    @Override
    public void execute(WaitState waitState, Action action) {
        switch (action) {
            case CompleteExternalTask complete -> {
                requireKind(waitState, WaitStateKind.EXTERNAL_TASK, action);
                completeExternalTask(waitState, complete);
            }
            case ThrowBpmnError bpmnError -> {
                requireKind(waitState, WaitStateKind.EXTERNAL_TASK, action);
                throwBpmnError(waitState, bpmnError);
            }
            case FailExternalTask failure -> {
                requireKind(waitState, WaitStateKind.EXTERNAL_TASK, action);
                failExternalTask(waitState, failure);
            }
            case CompleteUserTask complete -> {
                requireKind(waitState, WaitStateKind.USER_TASK, action);
                completeUserTask(waitState, complete);
            }
            case CorrelateMessage correlate -> {
                requireKind(waitState, WaitStateKind.MESSAGE_SUBSCRIPTION, action);
                correlateMessage(waitState, correlate);
            }
        }
    }

    private static void requireKind(WaitState waitState, WaitStateKind expected, Action action) {
        if (waitState.kind() != expected) {
            throw new EngineActionException(
                    "cannot execute " + action.getClass().getSimpleName() + " against a " + waitState.kind()
                            + " wait state (" + waitState.id() + "): expected " + expected);
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

    /**
     * Completes a user task via {@code POST /task/{id}/complete}. Unlike an external task,
     * there is no {@code workerId} to send: {@link #claim} never actually locks a user task
     * (see its Javadoc), so completion is not restricted to whichever caller last "claimed"
     * it.
     *
     * <p>Because {@link #claim} is a local no-op for user tasks (see its Javadoc), a
     * {@code 404} here (classified {@link CamundaFailureClassification#RESOURCE_MISSING})
     * unambiguously means a real race: another worker, a human through some other UI, or the
     * process itself already completed or removed this task between discovery and this call.
     * That is reported as {@link EngineWaitStateGoneException}, not
     * {@link EngineActionException}, so the loop journals the benign
     * {@link io.github.tokennudge.model.Outcome#CLAIM_LOST} instead of failing a user's test
     * through {@code failOnActionErrors(true)}.
     */
    private void completeUserTask(WaitState waitState, CompleteUserTask complete) {
        CompleteTaskRequest request = buildCompleteTaskRequest(waitState, complete);
        try {
            client.postNoContent("/task/" + waitState.id() + "/complete", request);
        } catch (EngineActionException e) {
            translateGoneRace(e);
        }
    }

    static CompleteTaskRequest buildCompleteTaskRequest(WaitState waitState, CompleteUserTask complete) {
        try {
            return new CompleteTaskRequest(encodeVariables(complete.variables()));
        } catch (RuntimeException e) {
            throw encodingFailure("CompleteUserTask", waitState, e);
        }
    }

    /**
     * Correlates a message via {@code POST /message}, targeting the wait state's process
     * instance either by id or by business key depending on
     * {@link CorrelateMessage#strategy()}, with {@code resultEnabled: true} so a duplicate
     * correlation (for example two process instances sharing a business key) comes back as a
     * definite {@link EngineActionException} rather than silently correlating to more than
     * one instance.
     *
     * <p>As for {@link #completeUserTask}, {@link #claim} is a local no-op for message
     * subscriptions, so a {@code 404} here (classified
     * {@link CamundaFailureClassification#RESOURCE_MISSING}) unambiguously means the
     * subscription (or its process instance) is already gone &mdash; another worker
     * correlated it first, or the process moved on some other way &mdash; and is reported as
     * {@link EngineWaitStateGoneException} rather than {@link EngineActionException}.
     */
    private void correlateMessage(WaitState waitState, CorrelateMessage correlate) {
        MessageCorrelationRequest request = buildMessageCorrelationRequest(waitState, correlate);
        try {
            client.postNoContent("/message", request);
        } catch (EngineActionException e) {
            translateGoneRace(e);
        }
    }

    /**
     * Rethrows a rejected user-task completion or message correlation request as
     * {@link EngineWaitStateGoneException} when the engine's own response unambiguously
     * reports the target as already gone ({@link CamundaFailureClassification#RESOURCE_MISSING}),
     * or rethrows the original {@link EngineActionException} unchanged otherwise (for example a
     * validation error, a wrong endpoint, or an ambiguous-correlation rejection).
     *
     * <p><strong>Deliberately not used for external-task completion, BPMN error, or
     * failure:</strong> those are only ever called after {@link #claim} has actually locked
     * the task on the engine (a real lock, unlike the local no-op for user tasks/messages), so
     * a {@code 404} there means the process instance was deleted out from under a lock this
     * worker still holds &mdash; a genuinely concerning condition, not a benign race, which
     * {@code Camunda7EngineAdapterFailureIT.engineRejectedActionEndsUpInActionErrors}
     * deliberately asserts still surfaces as {@code ACTION_FAILED}.
     *
     * @throws EngineWaitStateGoneException always, if {@code e} classifies as
     *                                       {@link CamundaFailureClassification#RESOURCE_MISSING}
     * @throws EngineActionException        always, otherwise (rethrows {@code e} itself)
     */
    private static void translateGoneRace(EngineActionException e) {
        if (CamundaFailureClassifier.classify(e) == CamundaFailureClassification.RESOURCE_MISSING) {
            throw new EngineWaitStateGoneException(e.getMessage(), e);
        }
        throw e;
    }

    static MessageCorrelationRequest buildMessageCorrelationRequest(WaitState waitState, CorrelateMessage correlate) {
        Map<String, TypedValueDto> variables;
        try {
            variables = encodeVariables(correlate.variables());
        } catch (RuntimeException e) {
            throw encodingFailure("CorrelateMessage", waitState, e);
        }
        return switch (correlate.strategy()) {
            case CorrelationStrategy.ByProcessInstance ignored ->
                    new MessageCorrelationRequest(waitState.name(), waitState.processInstanceId(), null, variables, true);
            case CorrelationStrategy.ByBusinessKey ignored -> {
                if (waitState.businessKey() == null) {
                    throw missingBusinessKeyFailure(waitState);
                }
                yield new MessageCorrelationRequest(waitState.name(), null, waitState.businessKey(), variables, true);
            }
        };
    }

    private static EngineActionException missingBusinessKeyFailure(WaitState waitState) {
        return new EngineActionException(
                "cannot correlate message \"" + waitState.name() + "\" (wait state " + waitState.id()
                        + ") by business key: this wait state's process instance has no business key");
    }

    private static Map<String, TypedValueDto> encodeVariables(Variables variables) {
        Map<String, TypedValueDto> encoded = new LinkedHashMap<>();
        variables.asMap().forEach((name, value) -> encoded.put(name, VariableCodec.encode(value)));
        return encoded;
    }

    private static EngineActionException encodingFailure(
            String actionName, WaitState waitState, RuntimeException cause) {
        return new EngineActionException(
                "could not encode " + actionName + " request for wait state " + waitState.id()
                        + " before sending it to the engine: " + cause.getMessage(), cause);
    }

    @Override
    public void close() {
        client.close();
    }
}
