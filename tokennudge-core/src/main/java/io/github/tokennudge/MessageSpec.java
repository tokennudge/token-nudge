package io.github.tokennudge;

import io.github.tokennudge.model.CorrelateMessage;
import io.github.tokennudge.model.VariableMatcher;
import io.github.tokennudge.model.WaitStateKind;

import java.util.List;
import java.util.Objects;

/**
 * Selects a message wait state (an intermediate catch event or receive task subscription) by
 * message name, and declares how it should be simulated (correlated) or verified.
 *
 * <p>Message start-event subscriptions are never selected: they have no process instance yet
 * to correlate to, and are not discovered as wait states at all (see
 * {@code io.github.tokennudge.camunda7.Camunda7EngineAdapter}).
 *
 * <p>Created via {@link TokenNudge#message(String)}.
 */
public final class MessageSpec extends WaitStateSpec<MessageSpec> {

    MessageSpec(String messageName) {
        super(WaitStateKind.MESSAGE_SUBSCRIPTION, messageName);
    }

    private MessageSpec(
            String messageName,
            String processDefinitionKey,
            String activityId,
            String businessKey,
            List<VariableMatcher> variableMatchers) {
        super(WaitStateKind.MESSAGE_SUBSCRIPTION, messageName, processDefinitionKey, activityId, businessKey,
                variableMatchers);
    }

    @Override
    protected MessageSpec copy(
            String processDefinitionKey, String activityId, String businessKey, List<VariableMatcher> variableMatchers) {
        return new MessageSpec(name(), processDefinitionKey, activityId, businessKey, variableMatchers);
    }

    /**
     * Declares that a matching message wait state should be correlated by process instance
     * id (see {@link TokenNudge#processInstance()}), with no process variables.
     *
     * @return a new simulation
     */
    public Simulation willCorrelate() {
        return willCorrelateBy(new CorrelationStrategy.ByProcessInstance(), Variables.empty());
    }

    /**
     * Declares that a matching message wait state should be correlated using the given
     * strategy, with no process variables.
     *
     * @param strategy how to target the process instance to correlate to, never {@code null}
     * @return a new simulation
     * @throws NullPointerException if {@code strategy} is {@code null}
     */
    public Simulation willCorrelateBy(CorrelationStrategy strategy) {
        return willCorrelateBy(strategy, Variables.empty());
    }

    /**
     * Declares that a matching message wait state should be correlated using the given
     * strategy, submitting the given process variables.
     *
     * @param strategy  how to target the process instance to correlate to, never {@code null}
     * @param variables the process variables to submit, never {@code null}
     * @return a new simulation
     * @throws NullPointerException if {@code strategy} or {@code variables} is {@code null}
     */
    public Simulation willCorrelateBy(CorrelationStrategy strategy, Variables variables) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(variables, "variables must not be null");
        return new Simulation(selector(), new CorrelateMessage(strategy, variables));
    }

    /**
     * A verification that a matching message wait state was correlated successfully.
     *
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    public Verification correlated() {
        return Verification.correlatedMessage(selector());
    }
}
