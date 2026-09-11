package io.github.tokennudge.internal;

import io.github.tokennudge.Simulation;
import io.github.tokennudge.SimulationId;
import io.github.tokennudge.Variables;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.model.WaitStateSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SimulationRegistryTest {

    private static Simulation simulation(String topic) {
        WaitStateSelector selector = new WaitStateSelector(WaitStateKind.EXTERNAL_TASK, topic, null, null, null, List.of());
        return new Simulation(selector, new CompleteExternalTask(Variables.empty()));
    }

    @Test
    void snapshotIsEmptyInitially() {
        assertThat(new SimulationRegistry().snapshot()).isEmpty();
    }

    @Test
    void snapshotReturnsAddedSimulations() {
        SimulationRegistry registry = new SimulationRegistry();
        Simulation a = simulation("a");
        registry.add(a);

        assertThat(registry.snapshot()).containsExactly(a);
    }

    @Test
    void snapshotOrdersByPriorityAscendingLowestFirst() {
        SimulationRegistry registry = new SimulationRegistry();
        Simulation low = simulation("low").atPriority(10);
        Simulation high = simulation("high").atPriority(1);
        Simulation mid = simulation("mid").atPriority(5);

        registry.add(low);
        registry.add(high);
        registry.add(mid);

        assertThat(registry.snapshot()).containsExactly(high, mid, low);
    }

    @Test
    void snapshotOrdersEqualPrioritiesByMostRecentlyRegisteredFirst() {
        SimulationRegistry registry = new SimulationRegistry();
        Simulation first = simulation("first");
        Simulation second = simulation("second");
        Simulation third = simulation("third");

        registry.add(first);
        registry.add(second);
        registry.add(third);

        assertThat(registry.snapshot()).containsExactly(third, second, first);
    }

    @Test
    void lowestPriorityWinsEvenIfRegisteredFirst() {
        SimulationRegistry registry = new SimulationRegistry();
        Simulation registeredFirstButLowPriority = simulation("first").atPriority(10);
        Simulation registeredSecondButHighPriority = simulation("second").atPriority(1);

        registry.add(registeredFirstButLowPriority);
        registry.add(registeredSecondButHighPriority);

        assertThat(registry.snapshot())
                .containsExactly(registeredSecondButHighPriority, registeredFirstButLowPriority);
    }

    @Test
    void removeById() {
        SimulationRegistry registry = new SimulationRegistry();
        Simulation a = simulation("a");
        Simulation b = simulation("b");
        registry.add(a);
        registry.add(b);

        boolean removed = registry.remove(a.id());

        assertThat(removed).isTrue();
        assertThat(registry.snapshot()).containsExactly(b);
    }

    @Test
    void removeUnknownIdReturnsFalse() {
        SimulationRegistry registry = new SimulationRegistry();
        assertThat(registry.remove(SimulationId.newId())).isFalse();
    }

    @Test
    void clearRemovesEverything() {
        SimulationRegistry registry = new SimulationRegistry();
        registry.add(simulation("a"));
        registry.add(simulation("b"));

        registry.clear();

        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void addRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new SimulationRegistry().add(null));
    }

    @Test
    void removeRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new SimulationRegistry().remove(null));
    }

    @Test
    void snapshotIsASnapshotNotALiveView() {
        SimulationRegistry registry = new SimulationRegistry();
        Simulation a = simulation("a");
        registry.add(a);

        List<Simulation> snapshot = registry.snapshot();
        registry.add(simulation("b"));

        assertThat(snapshot).containsExactly(a);
    }
}
