package io.github.tokennudge.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitStateSelectorTest {

    private static WaitStateSelector selector(
            WaitStateKind kind, String name, String pdk, String activityId, String businessKey) {
        return new WaitStateSelector(kind, name, pdk, activityId, businessKey, List.of());
    }

    @Test
    void matchesStaticRequiresSameKind() {
        WaitStateSelector selector = selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null);
        WaitState userTaskWithSameName = new WaitState(
                WaitStateKind.USER_TASK, "id", "charge-card", "pi", "pdk", "act", "bk", "ex", null);

        assertThat(selector.matchesStatic(userTaskWithSameName)).isFalse();
    }

    @Test
    void matchesStaticRequiresSameName() {
        WaitStateSelector selector = selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null);

        assertThat(selector.matchesStatic(WaitStates.externalTask("charge-card"))).isTrue();
        assertThat(selector.matchesStatic(WaitStates.externalTask("risk-check"))).isFalse();
    }

    @Test
    void nullProcessDefinitionKeyIsAWildcard() {
        WaitStateSelector selector = selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null);

        assertThat(selector.matchesStatic(WaitStates.externalTask("charge-card"))).isTrue();
    }

    @Test
    void specificProcessDefinitionKeyMustMatchExactly() {
        WaitStateSelector matching = selector(WaitStateKind.EXTERNAL_TASK, "charge-card", "payment", null, null);
        WaitStateSelector nonMatching =
                selector(WaitStateKind.EXTERNAL_TASK, "charge-card", "other-process", null, null);

        assertThat(matching.matchesStatic(WaitStates.externalTask("charge-card"))).isTrue();
        assertThat(nonMatching.matchesStatic(WaitStates.externalTask("charge-card"))).isFalse();
    }

    @Test
    void nullActivityIdIsAWildcard() {
        WaitStateSelector selector = selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null);
        assertThat(selector.matchesStatic(WaitStates.externalTask("charge-card"))).isTrue();
    }

    @Test
    void specificActivityIdMustMatchExactly() {
        WaitStateSelector matching =
                selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, "charge-card-activity", null);
        WaitStateSelector nonMatching =
                selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, "other-activity", null);

        assertThat(matching.matchesStatic(WaitStates.externalTask("charge-card"))).isTrue();
        assertThat(nonMatching.matchesStatic(WaitStates.externalTask("charge-card"))).isFalse();
    }

    @Test
    void nullBusinessKeyIsAWildcard() {
        WaitStateSelector selector = selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null);
        assertThat(selector.matchesStatic(WaitStates.externalTask("charge-card"))).isTrue();
    }

    @Test
    void specificBusinessKeyMustMatchExactly() {
        WaitStateSelector matching = selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, "order-42");
        WaitStateSelector nonMatching =
                selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, "order-99");

        assertThat(matching.matchesStatic(WaitStates.externalTask("charge-card"))).isTrue();
        assertThat(nonMatching.matchesStatic(WaitStates.externalTask("charge-card"))).isFalse();
    }

    @Test
    void matchesStaticRejectsNullWaitState() {
        WaitStateSelector selector = selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null);
        assertThatNullPointerException().isThrownBy(() -> selector.matchesStatic(null));
    }

    @Test
    void noVariableMatchersMeansMatchesVariablesIsAlwaysTrue() {
        WaitStateSelector selector = selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null);

        assertThat(selector.requiresVariables()).isFalse();
        assertThat(selector.matchesVariables(Map.of())).isTrue();
        assertThat(selector.matchesVariables(Map.of("anything", 1))).isTrue();
    }

    @Test
    void allVariableMatchersMustPass() {
        WaitStateSelector selector = new WaitStateSelector(
                WaitStateKind.EXTERNAL_TASK,
                "charge-card",
                null,
                null,
                null,
                List.of(new EqualsVariableMatcher("amount", 4200), new EqualsVariableMatcher("currency", "EUR")));

        assertThat(selector.requiresVariables()).isTrue();
        assertThat(selector.matchesVariables(Map.of("amount", 4200, "currency", "EUR"))).isTrue();
        assertThat(selector.matchesVariables(Map.of("amount", 4200, "currency", "USD"))).isFalse();
        assertThat(selector.matchesVariables(Map.of("amount", 4200))).isFalse();
    }

    @Test
    void matchesVariablesRejectsNullMap() {
        WaitStateSelector selector = selector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null);
        assertThatNullPointerException().isThrownBy(() -> selector.matchesVariables(null));
    }

    @Test
    void constructorRejectsNullKindAndName() {
        assertThatNullPointerException()
                .isThrownBy(() -> new WaitStateSelector(null, "charge-card", null, null, null, List.of()));
        assertThatNullPointerException()
                .isThrownBy(() -> new WaitStateSelector(WaitStateKind.EXTERNAL_TASK, null, null, null, null,
                        List.of()));
    }

    @Test
    void constructorRejectsNullVariableMatcherList() {
        assertThatNullPointerException().isThrownBy(
                () -> new WaitStateSelector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null, null));
    }

    @Test
    void variableMatchersAreDefensivelyCopiedAndUnmodifiable() {
        List<VariableMatcher> mutable = new ArrayList<>();
        mutable.add(new EqualsVariableMatcher("amount", 4200));
        WaitStateSelector selector =
                new WaitStateSelector(WaitStateKind.EXTERNAL_TASK, "charge-card", null, null, null, mutable);

        mutable.add(new EqualsVariableMatcher("currency", "EUR"));

        assertThat(selector.variableMatchers()).hasSize(1);
        assertThatThrownBy(() -> selector.variableMatchers().add(new EqualsVariableMatcher("x", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
