package io.github.tokennudge;

import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.model.WaitStateSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SimulationTest {

    private static WaitStateSelector selector() {
        return new WaitStateSelector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null, List.of());
    }

    @Test
    void defaultPriorityIsFive() {
        Simulation simulation = new Simulation(selector(), new CompleteExternalTask(Variables.empty()));
        assertThat(simulation.priority()).isEqualTo(Simulation.DEFAULT_PRIORITY).isEqualTo(5);
    }

    @Test
    void exposesGivenSelectorAndAction() {
        WaitStateSelector selector = selector();
        var action = new CompleteExternalTask(Variables.empty());
        Simulation simulation = new Simulation(selector, action);

        assertThat(simulation.selector()).isEqualTo(selector);
        assertThat(simulation.action()).isEqualTo(action);
    }

    @Test
    void hasAFreshIdPerConstruction() {
        Simulation a = new Simulation(selector(), new CompleteExternalTask(Variables.empty()));
        Simulation b = new Simulation(selector(), new CompleteExternalTask(Variables.empty()));
        assertThat(a.id()).isNotEqualTo(b.id());
    }

    @Test
    void atPriorityReturnsCopyPreservingIdSelectorAndAction() {
        Simulation original = new Simulation(selector(), new CompleteExternalTask(Variables.empty()));
        Simulation reprioritized = original.atPriority(1);

        assertThat(reprioritized.id()).isEqualTo(original.id());
        assertThat(reprioritized.selector()).isEqualTo(original.selector());
        assertThat(reprioritized.action()).isEqualTo(original.action());
        assertThat(reprioritized.priority()).isEqualTo(1);
        assertThat(original.priority()).isEqualTo(Simulation.DEFAULT_PRIORITY);
    }

    @Test
    void priorityBelowOneIsRejected() {
        Simulation simulation = new Simulation(selector(), new CompleteExternalTask(Variables.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> simulation.atPriority(0));
    }

    @Test
    void nullSelectorOrActionIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Simulation(null, new CompleteExternalTask(Variables.empty())));
        assertThatNullPointerException().isThrownBy(() -> new Simulation(selector(), null));
    }

    @Test
    void toStringContainsIdSelectorActionAndPriority() {
        Simulation simulation = new Simulation(selector(), new CompleteExternalTask(Variables.empty()));
        assertThat(simulation.toString())
                .contains(simulation.id().toString())
                .contains("charge-card")
                .contains("priority=" + Simulation.DEFAULT_PRIORITY);
    }

    @Test
    void equalsReturnsFalseForNullAndForNonSimulationArgument() {
        Simulation simulation = new Simulation(selector(), new CompleteExternalTask(Variables.empty()));
        assertThat(simulation.equals(null)).isFalse();
        assertThat(simulation.equals("not a simulation")).isFalse();
        assertThat(simulation.equals(simulation)).isTrue();
    }

    @Test
    void equalsAndHashCodeAreBasedOnAllFields() {
        WaitStateSelector selector = selector();
        var action = new CompleteExternalTask(Variables.empty());
        Simulation simulation = new Simulation(selector, action);
        Simulation copyAtSamePriority = simulation.atPriority(Simulation.DEFAULT_PRIORITY);
        Simulation reprioritized = simulation.atPriority(1);

        assertThat(simulation).isEqualTo(copyAtSamePriority).hasSameHashCodeAs(copyAtSamePriority);
        assertThat(simulation).isNotEqualTo(reprioritized);
    }
}
