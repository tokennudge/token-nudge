package io.github.tokennudge.model;

import io.github.tokennudge.Variables;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ActionTest {

    @Test
    void sealedHierarchyPermitsExhaustiveSwitchOverAllFiveVariants() {
        assertThat(describe(new CompleteExternalTask(Variables.empty()))).isEqualTo("complete");
        assertThat(describe(new ThrowBpmnError("CODE", null, Variables.empty()))).isEqualTo("bpmn-error");
        assertThat(describe(new FailExternalTask("boom", 0, Duration.ZERO))).isEqualTo("fail");
        assertThat(describe(new CompleteUserTask(Variables.empty()))).isEqualTo("complete-user-task");
        assertThat(describe(new CorrelateMessage(new io.github.tokennudge.CorrelationStrategy.ByProcessInstance(),
                Variables.empty()))).isEqualTo("correlate-message");
    }

    private static String describe(Action action) {
        return switch (action) {
            case CompleteExternalTask ignored -> "complete";
            case ThrowBpmnError ignored -> "bpmn-error";
            case FailExternalTask ignored -> "fail";
            case CompleteUserTask ignored -> "complete-user-task";
            case CorrelateMessage ignored -> "correlate-message";
        };
    }
}
