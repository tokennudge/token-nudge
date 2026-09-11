package io.github.tokennudge.model;

import io.github.tokennudge.Variables;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ThrowBpmnErrorTest {

    @Test
    void exposesGivenFields() {
        var action = new ThrowBpmnError("RISK_REJECTED", "risk too high", Variables.empty());

        assertThat(action.errorCode()).isEqualTo("RISK_REJECTED");
        assertThat(action.errorMessage()).isEqualTo("risk too high");
        assertThat(action.variables()).isEqualTo(Variables.empty());
    }

    @Test
    void errorMessageMayBeNull() {
        var action = new ThrowBpmnError("RISK_REJECTED", null, Variables.empty());
        assertThat(action.errorMessage()).isNull();
    }

    @Test
    void nullErrorCodeIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new ThrowBpmnError(null, "msg", Variables.empty()));
    }

    @Test
    void nullVariablesAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> new ThrowBpmnError("CODE", "msg", null));
    }
}
