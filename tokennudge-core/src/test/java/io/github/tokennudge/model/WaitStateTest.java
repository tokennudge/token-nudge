package io.github.tokennudge.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class WaitStateTest {

    @Test
    void allFieldsAreExposedAsGiven() {
        WaitState waitState = WaitStates.externalTask("charge-card");

        assertThat(waitState.kind()).isEqualTo(WaitStateKind.EXTERNAL_TASK);
        assertThat(waitState.id()).isEqualTo("task-1");
        assertThat(waitState.name()).isEqualTo("charge-card");
        assertThat(waitState.processInstanceId()).isEqualTo("process-instance-1");
        assertThat(waitState.processDefinitionKey()).isEqualTo("payment");
        assertThat(waitState.activityId()).isEqualTo("charge-card-activity");
        assertThat(waitState.businessKey()).isEqualTo("order-42");
        assertThat(waitState.executionId()).isEqualTo("execution-1");
        assertThat(waitState.tenantId()).isNull();
    }

    @Test
    void optionalFieldsMayBeNull() {
        WaitState waitState = new WaitState(
                WaitStateKind.MESSAGE_SUBSCRIPTION,
                "sub-1",
                "PaymentConfirmed",
                "process-instance-1",
                "payment",
                null,
                null,
                "execution-1",
                null);

        assertThat(waitState.activityId()).isNull();
        assertThat(waitState.businessKey()).isNull();
        assertThat(waitState.tenantId()).isNull();
    }

    @ParameterizedTest
    @MethodSource("requiredFieldMutators")
    void requiredFieldsMustNotBeNull(java.util.function.Supplier<WaitState> invalidFactory) {
        assertThatNullPointerException().isThrownBy(invalidFactory::get);
    }

    static Stream<java.util.function.Supplier<WaitState>> requiredFieldMutators() {
        return Stream.of(
                () -> new WaitState(null, "id", "name", "pi", "pdk", "act", "bk", "ex", null),
                () -> new WaitState(WaitStateKind.EXTERNAL_TASK, null, "name", "pi", "pdk", "act", "bk", "ex", null),
                () -> new WaitState(WaitStateKind.EXTERNAL_TASK, "id", null, "pi", "pdk", "act", "bk", "ex", null),
                () -> new WaitState(WaitStateKind.EXTERNAL_TASK, "id", "name", null, "pdk", "act", "bk", "ex", null),
                () -> new WaitState(WaitStateKind.EXTERNAL_TASK, "id", "name", "pi", null, "act", "bk", "ex", null),
                () -> new WaitState(WaitStateKind.EXTERNAL_TASK, "id", "name", "pi", "pdk", "act", "bk", null, null));
    }

    @Test
    void equalityIsValueBased() {
        assertThat(WaitStates.externalTask("charge-card")).isEqualTo(WaitStates.externalTask("charge-card"));
        assertThat(WaitStates.externalTask("charge-card")).isNotEqualTo(WaitStates.externalTask("risk-check"));
    }
}
