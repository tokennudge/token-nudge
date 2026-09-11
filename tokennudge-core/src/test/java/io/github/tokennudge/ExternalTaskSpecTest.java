package io.github.tokennudge;

import io.github.tokennudge.internal.SimulationRegistry;
import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.FailExternalTask;
import io.github.tokennudge.model.ThrowBpmnError;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.github.tokennudge.TokenNudge.externalTask;
import static io.github.tokennudge.TokenNudge.noVariables;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ExternalTaskSpecTest {

    /** Test-local stand-in for the future {@code TokenNudge#simulate(Simulation)} facade. */
    private final SimulationRegistry registry = new SimulationRegistry();

    private Simulation simulate(Simulation simulation) {
        registry.add(simulation);
        return simulation;
    }

    @Test
    void briefExampleCompilesAndRegisters() {
        simulate(externalTask("charge-card").inProcess("payment").willComplete(withVariables(Map.of("paid", true))));

        assertThat(registry.snapshot()).hasSize(1);
        Simulation registered = registry.snapshot().get(0);
        assertThat(registered.selector().kind()).isEqualTo(WaitStateKind.EXTERNAL_TASK);
        assertThat(registered.selector().name()).isEqualTo("charge-card");
        assertThat(registered.selector().processDefinitionKey()).isEqualTo("payment");
        assertThat(registered.action()).isEqualTo(new CompleteExternalTask(withVariables(Map.of("paid", true))));
    }

    @Test
    void willCompleteWithNoVariables() {
        Simulation simulation = externalTask("charge-card").willComplete();
        assertThat(simulation.action()).isEqualTo(new CompleteExternalTask(noVariables()));
    }

    @Test
    void willFailWithBpmnErrorCodeOnly() {
        Simulation simulation = externalTask("risk-check").willFailWithBpmnError("RISK_REJECTED");
        assertThat(simulation.action()).isEqualTo(new ThrowBpmnError("RISK_REJECTED", null, noVariables()));
    }

    @Test
    void willFailWithBpmnErrorFullOverload() {
        Simulation simulation = externalTask("risk-check")
                .willFailWithBpmnError("RISK_REJECTED", "too risky", withVariables(Map.of("score", 99)));
        assertThat(simulation.action())
                .isEqualTo(new ThrowBpmnError("RISK_REJECTED", "too risky", withVariables(Map.of("score", 99))));
    }

    @Test
    void willFailCreatesIncidentByDefault() {
        Simulation simulation = externalTask("charge-card").willFail("card declined");
        assertThat(simulation.action()).isEqualTo(new FailExternalTask("card declined", 0, Duration.ZERO));
    }

    @Test
    void willFailWithExplicitRetries() {
        Simulation simulation = externalTask("charge-card").willFail("transient error", 3, Duration.ofSeconds(10));
        assertThat(simulation.action())
                .isEqualTo(new FailExternalTask("transient error", 3, Duration.ofSeconds(10)));
    }

    @Test
    void builderMethodsReturnIndependentCopiesLeavingOriginalUnchanged() {
        ExternalTaskSpec original = externalTask("charge-card");
        ExternalTaskSpec withProcess = original.inProcess("payment");
        ExternalTaskSpec withActivity = withProcess.atActivity("charge-card-activity");
        ExternalTaskSpec withBusinessKey = withActivity.withBusinessKey("order-42");
        ExternalTaskSpec withVariable = withBusinessKey.withVariable("amount", 4200);

        assertThat(original.selector().processDefinitionKey()).isNull();
        assertThat(original.selector().activityId()).isNull();
        assertThat(original.selector().businessKey()).isNull();
        assertThat(original.selector().variableMatchers()).isEmpty();

        assertThat(withProcess.selector().processDefinitionKey()).isEqualTo("payment");
        assertThat(withProcess.selector().activityId()).isNull();

        assertThat(withActivity.selector().activityId()).isEqualTo("charge-card-activity");
        assertThat(withActivity.selector().businessKey()).isNull();

        assertThat(withBusinessKey.selector().businessKey()).isEqualTo("order-42");
        assertThat(withBusinessKey.selector().variableMatchers()).isEmpty();

        assertThat(withVariable.selector().variableMatchers()).hasSize(1);
    }

    @Test
    void withVariableMatchingBuildsAPredicateMatcherWithoutMutatingOriginal() {
        ExternalTaskSpec original = externalTask("charge-card");
        ExternalTaskSpec withMatcher = original.withVariableMatching("amount", v -> ((Integer) v) > 1000);

        assertThat(original.selector().variableMatchers()).isEmpty();
        assertThat(withMatcher.selector().variableMatchers()).hasSize(1);
        assertThat(withMatcher.selector().matchesVariables(Map.of("amount", 4200))).isTrue();
        assertThat(withMatcher.selector().matchesVariables(Map.of("amount", 1))).isFalse();
    }

    @Test
    void chainingMultipleVariableConstraintsAccumulates() {
        ExternalTaskSpec spec = externalTask("charge-card")
                .withVariable("currency", "EUR")
                .withVariable("amount", 4200);

        assertThat(spec.selector().variableMatchers()).hasSize(2);
        assertThat(spec.selector().matchesVariables(Map.of("currency", "EUR", "amount", 4200))).isTrue();
        assertThat(spec.selector().matchesVariables(Map.of("currency", "USD", "amount", 4200))).isFalse();
    }

    @Test
    void precedenceLowestPriorityWinsAmongOverlappingRules() {
        Simulation generic = simulate(externalTask("charge-card").willFail("declined"));
        Simulation specific = simulate(externalTask("charge-card").inProcess("payment").willComplete().atPriority(1));

        List<Simulation> snapshot = registry.snapshot();
        assertThat(snapshot).containsExactly(specific, generic);
    }

    @Test
    void precedenceMostRecentlyRegisteredWinsAmongEqualPriority() {
        Simulation first = simulate(externalTask("charge-card").willComplete());
        Simulation second = simulate(externalTask("charge-card").willFail("declined"));

        assertThat(registry.snapshot()).containsExactly(second, first);
    }

    @Test
    void firstMatchingSimulationInSnapshotOrderWinsForAGivenWaitState() {
        simulate(externalTask("charge-card").willFail("declined"));
        Simulation preferred = simulate(externalTask("charge-card").inProcess("payment").willComplete().atPriority(1));

        WaitState waitState = new WaitState(
                WaitStateKind.EXTERNAL_TASK, "task-1", "charge-card", "pi-1", "payment", "act", "bk", "ex-1", null);

        Simulation firstMatch = registry.snapshot().stream()
                .filter(s -> s.selector().matchesStatic(waitState))
                .findFirst()
                .orElseThrow();

        assertThat(firstMatch).isEqualTo(preferred);
    }

    @Test
    void nullTopicNameIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> externalTask(null));
    }

    @Test
    void nullArgumentsToBuilderMethodsAreRejected() {
        ExternalTaskSpec spec = externalTask("charge-card");
        assertThatNullPointerException().isThrownBy(() -> spec.inProcess(null));
        assertThatNullPointerException().isThrownBy(() -> spec.atActivity(null));
        assertThatNullPointerException().isThrownBy(() -> spec.withBusinessKey(null));
        assertThatNullPointerException().isThrownBy(() -> spec.withVariable(null, 1));
        assertThatNullPointerException().isThrownBy(() -> spec.withVariableMatching(null, v -> true));
        assertThatNullPointerException().isThrownBy(() -> spec.withVariableMatching("amount", null));
    }

    @Test
    void nullArgumentsToSimulationTerminalsAreRejected() {
        ExternalTaskSpec spec = externalTask("charge-card");
        assertThatNullPointerException().isThrownBy(() -> spec.willComplete(null));
        assertThatNullPointerException().isThrownBy(() -> spec.willFailWithBpmnError(null));
        assertThatNullPointerException()
                .isThrownBy(() -> spec.willFailWithBpmnError(null, "msg", noVariables()));
        assertThatNullPointerException()
                .isThrownBy(() -> spec.willFailWithBpmnError("CODE", "msg", null));
    }

    @Test
    void invalidFailExternalTaskArgumentsAreRejectedByTheUnderlyingAction() {
        ExternalTaskSpec spec = externalTask("charge-card");
        assertThatIllegalArgumentException().isThrownBy(() -> spec.willFail("boom", -1, Duration.ZERO));
    }

    @Test
    void actionIsAnAction() {
        Action action = externalTask("charge-card").willComplete().action();
        assertThat(action).isInstanceOf(CompleteExternalTask.class);
    }
}
