package io.github.tokennudge;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.CorrelateMessage;
import io.github.tokennudge.model.WaitStateKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.tokennudge.TokenNudge.businessKey;
import static io.github.tokennudge.TokenNudge.message;
import static io.github.tokennudge.TokenNudge.noVariables;
import static io.github.tokennudge.TokenNudge.processInstance;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MessageSpecTest {

    @Test
    void willCorrelateDefaultsToByProcessInstanceWithNoVariables() {
        Simulation simulation = message("PaymentConfirmed").willCorrelate();
        assertThat(simulation.selector().kind()).isEqualTo(WaitStateKind.MESSAGE_SUBSCRIPTION);
        assertThat(simulation.selector().name()).isEqualTo("PaymentConfirmed");
        assertThat(simulation.action()).isEqualTo(
                new CorrelateMessage(new CorrelationStrategy.ByProcessInstance(), noVariables()));
    }

    @Test
    void willCorrelateByStrategyWithNoVariables() {
        Simulation simulation = message("PaymentConfirmed").willCorrelateBy(businessKey());
        assertThat(simulation.action())
                .isEqualTo(new CorrelateMessage(new CorrelationStrategy.ByBusinessKey(), noVariables()));
    }

    @Test
    void willCorrelateByStrategyWithVariables() {
        Simulation simulation = message("PaymentConfirmed")
                .willCorrelateBy(processInstance(), withVariables(Map.of("confirmed", true)));
        assertThat(simulation.action()).isEqualTo(new CorrelateMessage(
                new CorrelationStrategy.ByProcessInstance(), withVariables(Map.of("confirmed", true))));
    }

    @Test
    void willCorrelateByRejectsNullStrategy() {
        MessageSpec spec = message("PaymentConfirmed");
        assertThatNullPointerException().isThrownBy(() -> spec.willCorrelateBy(null));
        assertThatNullPointerException().isThrownBy(() -> spec.willCorrelateBy(null, noVariables()));
    }

    @Test
    void willCorrelateByRejectsNullVariables() {
        MessageSpec spec = message("PaymentConfirmed");
        assertThatNullPointerException().isThrownBy(() -> spec.willCorrelateBy(businessKey(), null));
    }

    @Test
    void builderMethodsReturnIndependentCopiesLeavingOriginalUnchanged() {
        MessageSpec original = message("PaymentConfirmed");
        MessageSpec withProcess = original.inProcess("payment");

        assertThat(original.selector().processDefinitionKey()).isNull();
        assertThat(withProcess.selector().processDefinitionKey()).isEqualTo("payment");
    }

    @Test
    void nullMessageNameIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> message(null));
    }

    @Test
    void actionIsAnAction() {
        Action action = message("PaymentConfirmed").willCorrelate().action();
        assertThat(action).isInstanceOf(CorrelateMessage.class);
    }
}
