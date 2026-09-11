package io.github.tokennudge.camunda7;

import io.github.tokennudge.Variables;
import io.github.tokennudge.camunda7.dto.BpmnErrorRequest;
import io.github.tokennudge.camunda7.dto.CompleteExternalTaskRequest;
import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.camunda7.dto.FailureRequest;
import io.github.tokennudge.camunda7.dto.TypedValueDto;
import io.github.tokennudge.camunda7.dto.VariableInstanceDto;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.FailExternalTask;
import io.github.tokennudge.model.ThrowBpmnError;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.spi.EngineActionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
