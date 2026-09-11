package io.github.tokennudge.camunda7;

import io.github.tokennudge.CorrelationStrategy;
import io.github.tokennudge.Variables;
import io.github.tokennudge.camunda7.dto.BpmnErrorRequest;
import io.github.tokennudge.camunda7.dto.CompleteExternalTaskRequest;
import io.github.tokennudge.camunda7.dto.CompleteTaskRequest;
import io.github.tokennudge.camunda7.dto.EventSubscriptionDto;
import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.camunda7.dto.FailureRequest;
import io.github.tokennudge.camunda7.dto.MessageCorrelationRequest;
import io.github.tokennudge.camunda7.dto.TaskDto;
import io.github.tokennudge.camunda7.dto.TypedValueDto;
import io.github.tokennudge.camunda7.dto.VariableInstanceDto;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.CompleteUserTask;
import io.github.tokennudge.model.CorrelateMessage;
import io.github.tokennudge.model.FailExternalTask;
import io.github.tokennudge.model.ThrowBpmnError;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.spi.ClaimResult;
import io.github.tokennudge.spi.EngineActionException;
import io.github.tokennudge.spi.EngineConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Camunda7EngineAdapter}'s pure mapping/classification logic: DTO to
 * {@link WaitState} mapping, variable scope filtering, claim-failure classification, request
 * body construction for {@code complete}/{@code bpmnError}/{@code failure}, and encode-failure
 * wrapping. None of these need a real engine; the discover/claim/variables/execute HTTP paths
 * themselves are covered by the integration tests.
 */
class Camunda7EngineAdapterTest {

    @Test
    void mapsExternalTaskDtoToWaitState() {
        ExternalTaskDto dto = new ExternalTaskDto(
                "task-1", "it-charge", null, null, "pi-1", "exec-1", "def-id-1", "it-external-simple",
                "chargeTask", "ai-1", "tenant-1", null, null, false, 50, "order-1");

        WaitState waitState = Camunda7EngineAdapter.toWaitState(dto);

        assertThat(waitState.kind()).isEqualTo(WaitStateKind.EXTERNAL_TASK);
        assertThat(waitState.id()).isEqualTo("task-1");
        assertThat(waitState.name()).isEqualTo("it-charge");
        assertThat(waitState.processInstanceId()).isEqualTo("pi-1");
        assertThat(waitState.processDefinitionKey()).isEqualTo("it-external-simple");
        assertThat(waitState.activityId()).isEqualTo("chargeTask");
        assertThat(waitState.businessKey()).isEqualTo("order-1");
        assertThat(waitState.executionId()).isEqualTo("exec-1");
        assertThat(waitState.tenantId()).isEqualTo("tenant-1");
    }

    @Test
    void isLostClaimForLockedByOtherWorker() {
        assertThat(Camunda7EngineAdapter.isLostClaim(CamundaFailureClassification.LOCKED_BY_OTHER_WORKER)).isTrue();
    }

    @Test
    void isLostClaimForResourceMissing() {
        assertThat(Camunda7EngineAdapter.isLostClaim(CamundaFailureClassification.RESOURCE_MISSING)).isTrue();
    }

    @Test
    void isNotLostClaimForEndpointNotFound() {
        assertThat(Camunda7EngineAdapter.isLostClaim(CamundaFailureClassification.ENDPOINT_NOT_FOUND)).isFalse();
    }

    @Test
    void isNotLostClaimForUnclassified() {
        assertThat(Camunda7EngineAdapter.isLostClaim(CamundaFailureClassification.UNCLASSIFIED)).isFalse();
    }

    @Test
    void filtersToProcessScopedAndExecutionLocalVariablesOnly() {
        WaitState waitState = waitState("pi-1", "exec-2");
        VariableInstanceDto[] instances = {
            variable("processVar", "String", "process-value", "pi-1"),
            variable("localVar", "Integer", 4200, "exec-2"),
            variable("unrelatedScopeVar", "String", "elsewhere", "exec-99"),
        };

        Map<String, Object> variables = Camunda7EngineAdapter.filterVariablesForWaitState(waitState, instances);

        assertThat(variables).containsOnly(
                Map.entry("processVar", "process-value"),
                Map.entry("localVar", 4200));
    }

    @Test
    void executionLocalVariableOverridesProcessScopedVariableOfTheSameName() {
        WaitState waitState = waitState("pi-1", "exec-2");
        VariableInstanceDto[] instances = {
            variable("amount", "Integer", 100, "pi-1"),
            variable("amount", "Integer", 200, "exec-2"),
        };

        Map<String, Object> variables = Camunda7EngineAdapter.filterVariablesForWaitState(waitState, instances);

        assertThat(variables).containsExactly(Map.entry("amount", 200));
    }

    @Test
    void rootExecutionWaitStateOnlySeesProcessScopedVariables() {
        WaitState waitState = waitState("pi-1", "pi-1");
        VariableInstanceDto[] instances = {
            variable("amount", "Integer", 4200, "pi-1"),
        };

        Map<String, Object> variables = Camunda7EngineAdapter.filterVariablesForWaitState(waitState, instances);

        assertThat(variables).containsExactly(Map.entry("amount", 4200));
    }

    @Test
    void buildsCompleteRequestEncodingVariables() {
        WaitState waitState = waitState("pi-1", "pi-1");
        CompleteExternalTask complete = new CompleteExternalTask(withVariables(Map.of("amount", 4200)));

        CompleteExternalTaskRequest request =
                Camunda7EngineAdapter.buildCompleteRequest(waitState, "worker-1", complete);

        assertThat(request.workerId()).isEqualTo("worker-1");
        assertThat(request.variables()).containsExactly(Map.entry("amount", new TypedValueDto(4200, "Integer", null)));
    }

    @Test
    void completeRequestEncodeFailureIsWrappedAsADefiniteEngineActionException() {
        WaitState waitState = waitState("pi-1", "pi-1");
        CompleteExternalTask complete = new CompleteExternalTask(withVariables(Map.of("amount", BigDecimal.TEN)));

        assertThatThrownBy(() -> Camunda7EngineAdapter.buildCompleteRequest(waitState, "worker-1", complete))
                .isInstanceOf(EngineActionException.class)
                .hasMessageContaining("CompleteExternalTask")
                .hasMessageContaining("task-1")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsBpmnErrorRequestOmittingNullErrorMessageAndEncodingVariables() {
        WaitState waitState = waitState("pi-1", "pi-1");
        ThrowBpmnError bpmnError = new ThrowBpmnError("IT_REJECTED", null, withVariables(Map.of("reason", "fraud")));

        BpmnErrorRequest request = Camunda7EngineAdapter.buildBpmnErrorRequest(waitState, "worker-1", bpmnError);

        assertThat(request.workerId()).isEqualTo("worker-1");
        assertThat(request.errorCode()).isEqualTo("IT_REJECTED");
        assertThat(request.errorMessage()).isNull();
        assertThat(request.variables()).containsExactly(Map.entry("reason", new TypedValueDto("fraud", "String", null)));
    }

    @Test
    void buildsBpmnErrorRequestWithAnErrorMessage() {
        WaitState waitState = waitState("pi-1", "pi-1");
        ThrowBpmnError bpmnError = new ThrowBpmnError("IT_REJECTED", "rejected by test", Variables.empty());

        BpmnErrorRequest request = Camunda7EngineAdapter.buildBpmnErrorRequest(waitState, "worker-1", bpmnError);

        assertThat(request.errorMessage()).isEqualTo("rejected by test");
        assertThat(request.variables()).isEmpty();
    }

    @Test
    void bpmnErrorRequestEncodeFailureIsWrappedAsADefiniteEngineActionException() {
        WaitState waitState = waitState("pi-1", "pi-1");
        ThrowBpmnError bpmnError =
                new ThrowBpmnError("IT_REJECTED", null, withVariables(Map.of("amount", BigDecimal.TEN)));

        assertThatThrownBy(() -> Camunda7EngineAdapter.buildBpmnErrorRequest(waitState, "worker-1", bpmnError))
                .isInstanceOf(EngineActionException.class)
                .hasMessageContaining("ThrowBpmnError")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsFailureRequestConvertingRetryTimeoutToMilliseconds() {
        WaitState waitState = waitState("pi-1", "pi-1");
        FailExternalTask failure = new FailExternalTask("transient", 2, Duration.ofMinutes(5));

        FailureRequest request = Camunda7EngineAdapter.buildFailureRequest(waitState, "worker-1", failure);

        assertThat(request.workerId()).isEqualTo("worker-1");
        assertThat(request.errorMessage()).isEqualTo("transient");
        assertThat(request.retries()).isEqualTo(2);
        assertThat(request.retryTimeout()).isEqualTo(Duration.ofMinutes(5).toMillis());
    }

    @Test
    void buildsFailureRequestWithZeroRetries() {
        WaitState waitState = waitState("pi-1", "pi-1");
        FailExternalTask failure = new FailExternalTask("boom", 0, Duration.ZERO);

        FailureRequest request = Camunda7EngineAdapter.buildFailureRequest(waitState, "worker-1", failure);

        assertThat(request.retries()).isEqualTo(0);
        assertThat(request.retryTimeout()).isEqualTo(0L);
    }

    @Test
    void failureRequestRetryTimeoutOverflowIsWrappedAsADefiniteEngineActionExceptionNotOutcomeUnknown() {
        WaitState waitState = waitState("pi-1", "pi-1");
        // Duration.ofSeconds(Long.MAX_VALUE / 500).toMillis() overflows long arithmetic and
        // throws ArithmeticException, which must be caught here (not just IllegalArgumentException).
        FailExternalTask failure = new FailExternalTask("boom", 1, Duration.ofSeconds(Long.MAX_VALUE / 500));

        assertThatThrownBy(() -> Camunda7EngineAdapter.buildFailureRequest(waitState, "worker-1", failure))
                .isInstanceOf(EngineActionException.class)
                .hasMessageContaining("FailExternalTask")
                .hasMessageContaining("task-1")
                .hasMessageNotContaining("outcome unknown")
                .hasCauseInstanceOf(ArithmeticException.class);
    }

    @Test
    void executeRejectsANonExternalTaskWaitStateWithADefiniteEngineActionException() {
        Camunda7EngineAdapter adapter = new Camunda7EngineAdapter(new EngineConfig(
                URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1), Duration.ofSeconds(30),
                "worker-1", 50, Map.of()));
        WaitState userTask = new WaitState(
                WaitStateKind.USER_TASK, "task-1", "review", "pi-1", "it-review", "reviewTask", "order-1", "pi-1",
                null);

        assertThatThrownBy(() -> adapter.execute(userTask, new CompleteExternalTask(Variables.empty())))
                .isInstanceOf(EngineActionException.class)
                .hasMessageContaining("USER_TASK")
                .hasMessageContaining("task-1");
    }

    @Test
    void executeRejectsAnExternalTaskWaitStateForCompleteUserTask() {
        Camunda7EngineAdapter adapter = new Camunda7EngineAdapter(new EngineConfig(
                URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1), Duration.ofSeconds(30),
                "worker-1", 50, Map.of()));
        WaitState externalTask = waitState("pi-1", "pi-1");

        assertThatThrownBy(() -> adapter.execute(externalTask, new CompleteUserTask(Variables.empty())))
                .isInstanceOf(EngineActionException.class)
                .hasMessageContaining("EXTERNAL_TASK")
                .hasMessageContaining("task-1");
    }

    @Test
    void executeRejectsANonMessageWaitStateForCorrelateMessage() {
        Camunda7EngineAdapter adapter = new Camunda7EngineAdapter(new EngineConfig(
                URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1), Duration.ofSeconds(30),
                "worker-1", 50, Map.of()));
        WaitState externalTask = waitState("pi-1", "pi-1");

        assertThatThrownBy(() -> adapter.execute(
                externalTask, new CorrelateMessage(new CorrelationStrategy.ByProcessInstance(), Variables.empty())))
                .isInstanceOf(EngineActionException.class)
                .hasMessageContaining("MESSAGE_SUBSCRIPTION")
                .hasMessageContaining("task-1");
    }

    @Test
    void claimReturnsClaimedWithoutAnEngineCallForAUserTask() {
        Camunda7EngineAdapter adapter = new Camunda7EngineAdapter(new EngineConfig(
                URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1), Duration.ofSeconds(30),
                "worker-1", 50, Map.of()));
        WaitState userTask = new WaitState(
                WaitStateKind.USER_TASK, "task-1", "review", "pi-1", "it-review", "reviewTask", "order-1", "pi-1",
                null);

        assertThat(adapter.claim(userTask)).isEqualTo(ClaimResult.CLAIMED);
    }

    @Test
    void claimReturnsClaimedWithoutAnEngineCallForAMessageSubscription() {
        Camunda7EngineAdapter adapter = new Camunda7EngineAdapter(new EngineConfig(
                URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1), Duration.ofSeconds(30),
                "worker-1", 50, Map.of()));
        WaitState message = new WaitState(
                WaitStateKind.MESSAGE_SUBSCRIPTION, "sub-1", "PaymentConfirmed", "pi-1", "it-message-catch",
                "confirm", "order-1", "pi-1", null);

        assertThat(adapter.claim(message)).isEqualTo(ClaimResult.CLAIMED);
    }

    @Test
    void mapsTaskDtoToWaitState() {
        TaskDto dto = new TaskDto(
                "task-1", "Review", "review", "pi-1", "exec-1", "def-id-1", null, null, "2026-01-01T00:00:00.000+0000",
                null, 50, false, "tenant-1", null, null);
        ProcessInstanceResolver.ProcessInstanceInfo info =
                new ProcessInstanceResolver.ProcessInstanceInfo("order-1", "it-user-task");

        WaitState waitState = Camunda7EngineAdapter.toWaitState(dto, info);

        assertThat(waitState.kind()).isEqualTo(WaitStateKind.USER_TASK);
        assertThat(waitState.id()).isEqualTo("task-1");
        assertThat(waitState.name()).isEqualTo("review");
        assertThat(waitState.activityId()).isEqualTo("review");
        assertThat(waitState.processInstanceId()).isEqualTo("pi-1");
        assertThat(waitState.processDefinitionKey()).isEqualTo("it-user-task");
        assertThat(waitState.businessKey()).isEqualTo("order-1");
        assertThat(waitState.executionId()).isEqualTo("exec-1");
        assertThat(waitState.tenantId()).isEqualTo("tenant-1");
    }

    @Test
    void mapsEventSubscriptionDtoToWaitState() {
        EventSubscriptionDto dto = new EventSubscriptionDto(
                "sub-1", "message", "PaymentConfirmed", "exec-1", "pi-1", "confirm", "tenant-1",
                "2026-01-01T00:00:00.000+0000");
        ProcessInstanceResolver.ProcessInstanceInfo info =
                new ProcessInstanceResolver.ProcessInstanceInfo("order-1", "it-message-catch");

        WaitState waitState = Camunda7EngineAdapter.toWaitState(dto, info);

        assertThat(waitState.kind()).isEqualTo(WaitStateKind.MESSAGE_SUBSCRIPTION);
        assertThat(waitState.id()).isEqualTo("sub-1");
        assertThat(waitState.name()).isEqualTo("PaymentConfirmed");
        assertThat(waitState.activityId()).isEqualTo("confirm");
        assertThat(waitState.processInstanceId()).isEqualTo("pi-1");
        assertThat(waitState.processDefinitionKey()).isEqualTo("it-message-catch");
        assertThat(waitState.businessKey()).isEqualTo("order-1");
        assertThat(waitState.executionId()).isEqualTo("exec-1");
        assertThat(waitState.tenantId()).isEqualTo("tenant-1");
    }

    @Test
    void buildsCompleteTaskRequestEncodingVariables() {
        WaitState waitState = new WaitState(
                WaitStateKind.USER_TASK, "task-1", "review", "pi-1", "it-user-task", "review", "order-1", "pi-1",
                null);
        CompleteUserTask complete = new CompleteUserTask(withVariables(Map.of("approved", true)));

        CompleteTaskRequest request = Camunda7EngineAdapter.buildCompleteTaskRequest(waitState, complete);

        assertThat(request.variables()).containsExactly(Map.entry("approved", new TypedValueDto(true, "Boolean", null)));
    }

    @Test
    void completeTaskRequestEncodeFailureIsWrappedAsADefiniteEngineActionException() {
        WaitState waitState = new WaitState(
                WaitStateKind.USER_TASK, "task-1", "review", "pi-1", "it-user-task", "review", "order-1", "pi-1",
                null);
        CompleteUserTask complete = new CompleteUserTask(withVariables(Map.of("amount", BigDecimal.TEN)));

        assertThatThrownBy(() -> Camunda7EngineAdapter.buildCompleteTaskRequest(waitState, complete))
                .isInstanceOf(EngineActionException.class)
                .hasMessageContaining("CompleteUserTask")
                .hasMessageContaining("task-1")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsMessageCorrelationRequestByProcessInstance() {
        WaitState waitState = new WaitState(
                WaitStateKind.MESSAGE_SUBSCRIPTION, "sub-1", "PaymentConfirmed", "pi-1", "it-message-catch",
                "confirm", "order-1", "pi-1", null);
        CorrelateMessage correlate = new CorrelateMessage(
                new CorrelationStrategy.ByProcessInstance(), withVariables(Map.of("confirmed", true)));

        MessageCorrelationRequest request = Camunda7EngineAdapter.buildMessageCorrelationRequest(waitState, correlate);

        assertThat(request.messageName()).isEqualTo("PaymentConfirmed");
        assertThat(request.processInstanceId()).isEqualTo("pi-1");
        assertThat(request.businessKey()).isNull();
        assertThat(request.resultEnabled()).isTrue();
        assertThat(request.processVariables())
                .containsExactly(Map.entry("confirmed", new TypedValueDto(true, "Boolean", null)));
    }

    @Test
    void buildsMessageCorrelationRequestByBusinessKey() {
        WaitState waitState = new WaitState(
                WaitStateKind.MESSAGE_SUBSCRIPTION, "sub-1", "PaymentConfirmed", "pi-1", "it-message-catch",
                "confirm", "order-1", "pi-1", null);
        CorrelateMessage correlate = new CorrelateMessage(new CorrelationStrategy.ByBusinessKey(), Variables.empty());

        MessageCorrelationRequest request = Camunda7EngineAdapter.buildMessageCorrelationRequest(waitState, correlate);

        assertThat(request.messageName()).isEqualTo("PaymentConfirmed");
        assertThat(request.processInstanceId()).isNull();
        assertThat(request.businessKey()).isEqualTo("order-1");
    }

    @Test
    void byBusinessKeyWithNoBusinessKeyIsADefiniteEngineActionException() {
        WaitState waitState = new WaitState(
                WaitStateKind.MESSAGE_SUBSCRIPTION, "sub-1", "PaymentConfirmed", "pi-1", "it-message-catch",
                "confirm", null, "pi-1", null);
        CorrelateMessage correlate = new CorrelateMessage(new CorrelationStrategy.ByBusinessKey(), Variables.empty());

        assertThatThrownBy(() -> Camunda7EngineAdapter.buildMessageCorrelationRequest(waitState, correlate))
                .isInstanceOf(EngineActionException.class)
                .hasMessageContaining("PaymentConfirmed")
                .hasMessageContaining("business key");
    }

    @Test
    void messageCorrelationRequestEncodeFailureIsWrappedAsADefiniteEngineActionException() {
        WaitState waitState = new WaitState(
                WaitStateKind.MESSAGE_SUBSCRIPTION, "sub-1", "PaymentConfirmed", "pi-1", "it-message-catch",
                "confirm", "order-1", "pi-1", null);
        CorrelateMessage correlate =
                new CorrelateMessage(new CorrelationStrategy.ByProcessInstance(), withVariables(Map.of("amount", BigDecimal.TEN)));

        assertThatThrownBy(() -> Camunda7EngineAdapter.buildMessageCorrelationRequest(waitState, correlate))
                .isInstanceOf(EngineActionException.class)
                .hasMessageContaining("CorrelateMessage")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private static WaitState waitState(String processInstanceId, String executionId) {
        return new WaitState(
                WaitStateKind.EXTERNAL_TASK, "task-1", "it-charge", processInstanceId, "it-external-simple",
                "chargeTask", "order-1", executionId, null);
    }

    private static VariableInstanceDto variable(String name, String type, Object value, String executionId) {
        return new VariableInstanceDto(
                "vi-" + name, name, type, value, null, "def-1", "pi-1", executionId, null, null, null);
    }
}
