package io.github.tokennudge;

import io.github.tokennudge.model.EqualsVariableMatcher;
import io.github.tokennudge.model.PredicateVariableMatcher;
import io.github.tokennudge.model.VariableMatcher;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.model.WaitStateSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Base class for the fluent, immutable DSL used to select a wait state, shared by the
 * simulation and verification sides of the API (for example {@link ExternalTaskSpec}).
 *
 * <p>Every builder method returns a new, independent instance; the receiver is never
 * mutated. As of this version, only {@link ExternalTaskSpec} is permitted; user-task and
 * message specs are added in later iterations.
 *
 * @param <S> the concrete spec subtype, so that builder methods can return {@code S}
 *            instead of the abstract base type
 */
public abstract sealed class WaitStateSpec<S extends WaitStateSpec<S>> permits ExternalTaskSpec {

    private final WaitStateKind kind;
    private final String name;
    private final String processDefinitionKey;
    private final String activityId;
    private final String businessKey;
    private final List<VariableMatcher> variableMatchers;

    /**
     * Creates a new spec with only the required {@code kind}/{@code name} set; every other
     * static field is unset (matches any value) and there are no variable matchers.
     *
     * @param kind the kind of wait state this spec selects, never {@code null}
     * @param name the topic name, task definition key, or message name, never {@code null}
     */
    protected WaitStateSpec(WaitStateKind kind, String name) {
        this(kind, name, null, null, null, List.of());
    }

    /**
     * Creates a new spec with every field given explicitly. Used by subclasses to
     * implement {@link #copy(String, String, String, List)}.
     *
     * @param kind                 the kind of wait state this spec selects, never
     *                             {@code null}
     * @param name                 the topic name, task definition key, or message name,
     *                             never {@code null}
     * @param processDefinitionKey the process definition key, or {@code null} to match any
     * @param activityId           the activity id, or {@code null} to match any
     * @param businessKey          the business key, or {@code null} to match any
     * @param variableMatchers     the variable matchers, never {@code null}
     */
    protected WaitStateSpec(
            WaitStateKind kind,
            String name,
            String processDefinitionKey,
            String activityId,
            String businessKey,
            List<VariableMatcher> variableMatchers) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.processDefinitionKey = processDefinitionKey;
        this.activityId = activityId;
        this.businessKey = businessKey;
        Objects.requireNonNull(variableMatchers, "variableMatchers must not be null");
        this.variableMatchers = List.copyOf(variableMatchers);
    }

    /**
     * Returns the topic name, task definition key, or message name this spec selects.
     *
     * @return the name, never {@code null}
     */
    protected final String name() {
        return name;
    }

    /**
     * Creates a copy of this spec with the given static fields and variable matchers,
     * keeping the concrete subtype. Implemented by subclasses so that base-class builder
     * methods can return {@code S} without reflection.
     *
     * @param processDefinitionKey the process definition key, or {@code null}
     * @param activityId           the activity id, or {@code null}
     * @param businessKey          the business key, or {@code null}
     * @param variableMatchers     the variable matchers, never {@code null}
     * @return a new instance of the concrete subtype with the given fields
     */
    protected abstract S copy(
            String processDefinitionKey, String activityId, String businessKey, List<VariableMatcher> variableMatchers);

    /**
     * Restricts this spec to wait states belonging to the given process definition.
     *
     * @param processDefinitionKey the process definition key to require, never {@code null}
     * @return a new spec with the process definition key set
     * @throws NullPointerException if {@code processDefinitionKey} is {@code null}
     */
    public S inProcess(String processDefinitionKey) {
        Objects.requireNonNull(processDefinitionKey, "processDefinitionKey must not be null");
        return copy(processDefinitionKey, activityId, businessKey, variableMatchers);
    }

    /**
     * Restricts this spec to wait states at the given BPMN activity id.
     *
     * @param activityId the activity id to require, never {@code null}
     * @return a new spec with the activity id set
     * @throws NullPointerException if {@code activityId} is {@code null}
     */
    public S atActivity(String activityId) {
        Objects.requireNonNull(activityId, "activityId must not be null");
        return copy(processDefinitionKey, activityId, businessKey, variableMatchers);
    }

    /**
     * Restricts this spec to wait states whose process instance has the given business key.
     *
     * @param businessKey the business key to require, never {@code null}
     * @return a new spec with the business key set
     * @throws NullPointerException if {@code businessKey} is {@code null}
     */
    public S withBusinessKey(String businessKey) {
        Objects.requireNonNull(businessKey, "businessKey must not be null");
        return copy(processDefinitionKey, activityId, businessKey, variableMatchers);
    }

    /**
     * Adds a constraint requiring the named process variable to equal the given value.
     *
     * <p>Equality is numeric-normalized (see
     * {@link io.github.tokennudge.model.EqualsVariableMatcher}): an expected {@code int}
     * value of {@code 4200} also matches an actual {@code long} of {@code 4200}.
     *
     * @param name          the variable name, never {@code null}
     * @param expectedValue the expected value, may be {@code null} to require the variable
     *                      to be present and {@code null}
     * @return a new spec with the additional constraint
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public S withVariable(String name, Object expectedValue) {
        Objects.requireNonNull(name, "name must not be null");
        return copy(
                processDefinitionKey, activityId, businessKey, appended(new EqualsVariableMatcher(name, expectedValue)));
    }

    /**
     * Adds a constraint requiring the named process variable to satisfy an arbitrary
     * condition.
     *
     * @param name      the variable name, never {@code null}
     * @param condition the condition the variable's value must satisfy, never {@code null}
     * @return a new spec with the additional constraint
     * @throws NullPointerException if {@code name} or {@code condition} is {@code null}
     */
    public S withVariableMatching(String name, Predicate<Object> condition) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        return copy(
                processDefinitionKey,
                activityId,
                businessKey,
                appended(new PredicateVariableMatcher(name, condition)));
    }

    private List<VariableMatcher> appended(VariableMatcher matcher) {
        List<VariableMatcher> withMatcher = new ArrayList<>(variableMatchers);
        withMatcher.add(matcher);
        return withMatcher;
    }

    /**
     * Builds the engine-agnostic selector described by this spec so far.
     *
     * @return a selector combining this spec's static fields and variable matchers
     */
    public final WaitStateSelector selector() {
        return new WaitStateSelector(kind, name, processDefinitionKey, activityId, businessKey, variableMatchers);
    }

    /**
     * A verification that the wait state described by this spec was observed at all,
     * whatever the outcome (matched by a simulation or not).
     *
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    public Verification reached() {
        return Verification.reached(selector());
    }

    /**
     * A verification that the wait state described by this spec was handled, i.e. a
     * simulation matched it and its action executed successfully.
     *
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    public Verification handled() {
        return Verification.handled(selector());
    }
}
