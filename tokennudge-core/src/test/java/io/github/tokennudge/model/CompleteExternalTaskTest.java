package io.github.tokennudge.model;

import io.github.tokennudge.Variables;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CompleteExternalTaskTest {

    @Test
    void exposesGivenVariables() {
        Variables variables = Variables.of(Map.of("paid", true));
        assertThat(new CompleteExternalTask(variables).variables()).isEqualTo(variables);
    }

    @Test
    void emptyVariablesAreAllowed() {
        assertThat(new CompleteExternalTask(Variables.empty()).variables()).isEqualTo(Variables.empty());
    }

    @Test
    void nullVariablesAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> new CompleteExternalTask(null));
    }

    @Test
    void isAnAction() {
        Action action = new CompleteExternalTask(Variables.empty());
        assertThat(action).isInstanceOf(Action.class);
    }
}
