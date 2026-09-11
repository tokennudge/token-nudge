package io.github.tokennudge;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.CompleteUserTask;
import io.github.tokennudge.model.WaitStateKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.tokennudge.TokenNudge.noVariables;
import static io.github.tokennudge.TokenNudge.userTask;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class UserTaskSpecTest {

    @Test
    void willCompleteWithNoVariables() {
        Simulation simulation = userTask("approve-shipment").willComplete();
        assertThat(simulation.selector().kind()).isEqualTo(WaitStateKind.USER_TASK);
        assertThat(simulation.selector().name()).isEqualTo("approve-shipment");
        assertThat(simulation.action()).isEqualTo(new CompleteUserTask(noVariables()));
    }

    @Test
    void willCompleteWithVariables() {
        Simulation simulation = userTask("approve-shipment").willComplete(withVariables(Map.of("approved", true)));
        assertThat(simulation.action()).isEqualTo(new CompleteUserTask(withVariables(Map.of("approved", true))));
    }

    @Test
    void willCompleteRejectsNullVariables() {
        UserTaskSpec spec = userTask("approve-shipment");
        assertThatNullPointerException().isThrownBy(() -> spec.willComplete(null));
    }

    @Test
    void builderMethodsReturnIndependentCopiesLeavingOriginalUnchanged() {
        UserTaskSpec original = userTask("approve-shipment");
        UserTaskSpec withProcess = original.inProcess("payment");

        assertThat(original.selector().processDefinitionKey()).isNull();
        assertThat(withProcess.selector().processDefinitionKey()).isEqualTo("payment");
    }

    @Test
    void nullTaskDefinitionKeyIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> userTask(null));
    }

    @Test
    void actionIsAnAction() {
        Action action = userTask("approve-shipment").willComplete().action();
        assertThat(action).isInstanceOf(CompleteUserTask.class);
    }
}
