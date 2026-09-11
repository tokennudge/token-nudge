package io.github.tokennudge;

import io.github.tokennudge.model.CompleteUserTask;
import io.github.tokennudge.model.VariableMatcher;
import io.github.tokennudge.model.WaitStateKind;

import java.util.List;
import java.util.Objects;

/**
 * Selects a (human) user task by task definition key, and declares how it should be
 * simulated (completed) or verified.
 *
 * <p>Created via {@link TokenNudge#userTask(String)}.
 */
public final class UserTaskSpec extends WaitStateSpec<UserTaskSpec> {

    UserTaskSpec(String taskDefinitionKey) {
        super(WaitStateKind.USER_TASK, taskDefinitionKey);
    }

    private UserTaskSpec(
            String taskDefinitionKey,
            String processDefinitionKey,
            String activityId,
            String businessKey,
            List<VariableMatcher> variableMatchers) {
        super(WaitStateKind.USER_TASK, taskDefinitionKey, processDefinitionKey, activityId, businessKey,
                variableMatchers);
    }

    @Override
    protected UserTaskSpec copy(
            String processDefinitionKey, String activityId, String businessKey, List<VariableMatcher> variableMatchers) {
        return new UserTaskSpec(name(), processDefinitionKey, activityId, businessKey, variableMatchers);
    }

    /**
     * Declares that a matching user task should be completed with no output variables.
     *
     * @return a new simulation
     */
    public Simulation willComplete() {
        return willComplete(Variables.empty());
    }

    /**
     * Declares that a matching user task should be completed with the given output
     * variables.
     *
     * @param variables the output variables to submit, never {@code null}
     * @return a new simulation
     * @throws NullPointerException if {@code variables} is {@code null}
     */
    public Simulation willComplete(Variables variables) {
        Objects.requireNonNull(variables, "variables must not be null");
        return new Simulation(selector(), new CompleteUserTask(variables));
    }

    /**
     * A verification that a matching user task was completed successfully.
     *
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    public Verification completed() {
        return Verification.completedUserTask(selector());
    }
}
