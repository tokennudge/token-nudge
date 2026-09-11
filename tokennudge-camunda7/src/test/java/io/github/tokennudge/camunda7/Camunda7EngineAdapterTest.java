package io.github.tokennudge.camunda7;

import io.github.tokennudge.Variables;
import io.github.tokennudge.camunda7.dto.ExternalTaskDto;
import io.github.tokennudge.camunda7.dto.VariableInstanceDto;
import io.github.tokennudge.model.FailExternalTask;
import io.github.tokennudge.model.ThrowBpmnError;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.spi.EngineActionException;
import io.github.tokennudge.spi.EngineConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Camunda7EngineAdapter}'s pure mapping/classification logic: DTO to
 * {@link WaitState} mapping, variable scope filtering, claim-failure classification, and
 * unsupported-action rejection. None of these need a real engine; the discover/claim/
 * variables/execute HTTP paths themselves are covered by the integration tests.
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
    void throwBpmnErrorIsRejectedAsNotSupportedYet() {
        try (Camunda7EngineAdapter adapter = newAdapter()) {
            WaitState waitState = waitState("pi-1", "pi-1");
            assertThatThrownBy(() -> adapter.execute(
                    waitState, new ThrowBpmnError("IT_REJECTED", null, Variables.empty())))
                    .isInstanceOf(EngineActionException.class)
                    .hasMessageContaining("ThrowBpmnError")
                    .hasMessageContaining("not supported");
        }
    }

    @Test
    void failExternalTaskIsRejectedAsNotSupportedYet() {
        try (Camunda7EngineAdapter adapter = newAdapter()) {
            WaitState waitState = waitState("pi-1", "pi-1");
            assertThatThrownBy(() -> adapter.execute(
                    waitState, new FailExternalTask("boom", 0, Duration.ZERO)))
                    .isInstanceOf(EngineActionException.class)
                    .hasMessageContaining("FailExternalTask")
                    .hasMessageContaining("not supported");
        }
    }

    private static Camunda7EngineAdapter newAdapter() {
        return new Camunda7EngineAdapter(new EngineConfig(
                URI.create("http://localhost:1/engine-rest"), Duration.ofSeconds(1), Duration.ofSeconds(30),
                "test-worker", 50, Map.of()));
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
