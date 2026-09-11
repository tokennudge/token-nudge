package io.github.tokennudge;

import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.FailExternalTask;
import io.github.tokennudge.model.ThrowBpmnError;
import io.github.tokennudge.model.VariableMatcher;
import io.github.tokennudge.model.WaitStateKind;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Selects an external task by topic name, and declares how it should be simulated
 * (completed, failed with a BPMN error, or technically failed) or verified.
 *
 * <p>Created via {@link TokenNudge#externalTask(String)}.
 */
public final class ExternalTaskSpec extends WaitStateSpec<ExternalTaskSpec> {

    ExternalTaskSpec(String topicName) {
        super(WaitStateKind.EXTERNAL_TASK, topicName);
    }

    private ExternalTaskSpec(
            String topicName,
            String processDefinitionKey,
            String activityId,
            String businessKey,
            List<VariableMatcher> variableMatchers) {
        super(WaitStateKind.EXTERNAL_TASK, topicName, processDefinitionKey, activityId, businessKey, variableMatchers);
    }

    @Override
    protected ExternalTaskSpec copy(
            String processDefinitionKey, String activityId, String businessKey, List<VariableMatcher> variableMatchers) {
        return new ExternalTaskSpec(name(), processDefinitionKey, activityId, businessKey, variableMatchers);
    }

    /**
     * Declares that a matching external task should be completed with no output
     * variables.
     *
     * @return a new simulation
     */
    public Simulation willComplete() {
        return willComplete(Variables.empty());
    }

    /**
     * Declares that a matching external task should be completed with the given output
     * variables.
     *
     * @param variables the output variables to submit, never {@code null}
     * @return a new simulation
     * @throws NullPointerException if {@code variables} is {@code null}
     */
    public Simulation willComplete(Variables variables) {
        Objects.requireNonNull(variables, "variables must not be null");
        return new Simulation(selector(), new CompleteExternalTask(variables));
    }

    /**
     * Declares that a matching external task should fail with the given BPMN error code
     * and no output variables.
     *
     * @param errorCode the BPMN error code, never {@code null}
     * @return a new simulation
     * @throws NullPointerException if {@code errorCode} is {@code null}
     */
    public Simulation willFailWithBpmnError(String errorCode) {
        return willFailWithBpmnError(errorCode, null, Variables.empty());
    }

    /**
     * Declares that a matching external task should fail with the given BPMN error code,
     * error message, and output variables.
     *
     * @param errorCode    the BPMN error code, never {@code null}
     * @param errorMessage a human-readable error message, or {@code null}
     * @param variables    the output variables to submit alongside the error, never
     *                     {@code null}
     * @return a new simulation
     * @throws NullPointerException if {@code errorCode} or {@code variables} is
     *                              {@code null}
     */
    public Simulation willFailWithBpmnError(String errorCode, String errorMessage, Variables variables) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(variables, "variables must not be null");
        return new Simulation(selector(), new ThrowBpmnError(errorCode, errorMessage, variables));
    }

    /**
     * Declares that a matching external task should fail technically, with zero retries
     * left, so the engine raises an incident immediately.
     *
     * @param errorMessage the failure message, never {@code null}
     * @return a new simulation
     * @throws NullPointerException if {@code errorMessage} is {@code null}
     */
    public Simulation willFail(String errorMessage) {
        return willFail(errorMessage, 0, Duration.ZERO);
    }

    /**
     * Declares that a matching external task should fail technically, leaving the given
     * number of retries and retry timeout.
     *
     * @param errorMessage the failure message, never {@code null}
     * @param retries      the number of retries left after this failure; {@code 0} creates
     *                     an incident, must not be negative
     * @param retryTimeout the delay before the task becomes available again for retry,
     *                     never {@code null}, must not be negative. With {@code retries > 0}
     *                     and {@link Duration#ZERO}, the task is immediately fetchable again;
     *                     this run leaves it alone (already recorded as handled), but a later
     *                     {@code reset()} lets it be discovered and handled again
     * @return a new simulation
     * @throws NullPointerException     if {@code errorMessage} or {@code retryTimeout} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code retries} or {@code retryTimeout} is
     *                                  negative
     */
    public Simulation willFail(String errorMessage, int retries, Duration retryTimeout) {
        return new Simulation(selector(), new FailExternalTask(errorMessage, retries, retryTimeout));
    }

    /**
     * A verification that a matching external task was completed successfully.
     *
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    public Verification completed() {
        return Verification.completedExternalTask(selector());
    }

    /**
     * A verification that a matching external task failed with a BPMN error, of any error
     * code.
     *
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    public Verification failedWithBpmnError() {
        return Verification.failedWithBpmnError(selector(), null);
    }

    /**
     * A verification that a matching external task failed with the given BPMN error code.
     *
     * @param errorCode the BPMN error code to require, never {@code null}
     * @return a new verification with the default expectation of {@code atLeast(1)}
     * @throws NullPointerException if {@code errorCode} is {@code null}
     */
    public Verification failedWithBpmnError(String errorCode) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return Verification.failedWithBpmnError(selector(), errorCode);
    }

    /**
     * A verification that a matching external task failed technically (not with a BPMN
     * error).
     *
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    public Verification failed() {
        return Verification.failedExternalTask(selector());
    }
}
