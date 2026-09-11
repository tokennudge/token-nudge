package io.github.tokennudge;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class TokenNudgeTest {

    @Test
    void externalTaskCreatesASpecForTheGivenTopic() {
        ExternalTaskSpec spec = TokenNudge.externalTask("charge-card");
        assertThat(spec.selector().name()).isEqualTo("charge-card");
    }

    @Test
    void externalTaskRejectsNullTopic() {
        assertThatNullPointerException().isThrownBy(() -> TokenNudge.externalTask(null));
    }

    @Test
    void withVariablesWrapsTheGivenMap() {
        Variables variables = TokenNudge.withVariables(Map.of("paid", true));
        assertThat(variables.asMap()).containsEntry("paid", true);
    }

    @Test
    void withVariablesRejectsNullMap() {
        assertThatNullPointerException().isThrownBy(() -> TokenNudge.withVariables(null));
    }

    @Test
    void withVariablesCopiesDefensively() {
        Map<String, Object> source = new HashMap<>();
        source.put("amount", 100);
        Variables variables = TokenNudge.withVariables(source);
        source.put("amount", 200);
        assertThat(variables.asMap()).containsEntry("amount", 100);
    }

    @Test
    void noVariablesIsEmpty() {
        assertThat(TokenNudge.noVariables()).isEqualTo(Variables.empty());
        assertThat(TokenNudge.noVariables().isEmpty()).isTrue();
    }

    @Test
    void userTaskCreatesASpecForTheGivenTaskDefinitionKey() {
        UserTaskSpec spec = TokenNudge.userTask("approve-shipment");
        assertThat(spec.selector().name()).isEqualTo("approve-shipment");
    }

    @Test
    void userTaskRejectsNullTaskDefinitionKey() {
        assertThatNullPointerException().isThrownBy(() -> TokenNudge.userTask(null));
    }

    @Test
    void messageCreatesASpecForTheGivenMessageName() {
        MessageSpec spec = TokenNudge.message("PaymentConfirmed");
        assertThat(spec.selector().name()).isEqualTo("PaymentConfirmed");
    }

    @Test
    void messageRejectsNullMessageName() {
        assertThatNullPointerException().isThrownBy(() -> TokenNudge.message(null));
    }

    @Test
    void businessKeyReturnsAByBusinessKeyStrategy() {
        assertThat(TokenNudge.businessKey()).isEqualTo(new CorrelationStrategy.ByBusinessKey());
    }

    @Test
    void processInstanceReturnsAByProcessInstanceStrategy() {
        assertThat(TokenNudge.processInstance()).isEqualTo(new CorrelationStrategy.ByProcessInstance());
    }
}
