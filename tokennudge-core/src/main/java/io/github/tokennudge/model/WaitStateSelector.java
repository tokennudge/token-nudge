package io.github.tokennudge.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Selects which {@link WaitState}s a simulation rule applies to.
 *
 * <p>A selector combines static matchers (kind, name, and optionally process definition
 * key, activity id, business key) evaluated against a {@link WaitState} itself, with
 * dynamic {@link VariableMatcher}s evaluated against the process variables visible at
 * that wait state. Static matching is cheap and can be evaluated without engine access;
 * variable matching typically requires an extra round-trip to fetch variables, so callers
 * should check {@link #matchesStatic(WaitState)} first and only fetch variables when
 * {@link #requiresVariables()} is {@code true}.
 *
 * @param kind                 the kind of wait state this selector applies to, never
 *                             {@code null}
 * @param name                 the topic name, task definition key, or message name to
 *                             match, never {@code null}
 * @param processDefinitionKey the process definition key to require, or {@code null} to
 *                             match any process definition
 * @param activityId           the BPMN activity id to require, or {@code null} to match
 *                             any activity
 * @param businessKey          the business key to require, or {@code null} to match any
 *                             business key
 * @param variableMatchers     the variable matchers that must all pass; an empty list
 *                             means no variable constraints
 */
public record WaitStateSelector(
        WaitStateKind kind,
        String name,
        String processDefinitionKey,
        String activityId,
        String businessKey,
        List<VariableMatcher> variableMatchers) {

    /**
     * Validates required fields and makes a defensive, unmodifiable copy of
     * {@code variableMatchers}.
     *
     * @throws NullPointerException if {@code kind}, {@code name}, or
     *                              {@code variableMatchers} is {@code null}, or if
     *                              {@code variableMatchers} contains a {@code null}
     *                              element
     */
    public WaitStateSelector {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(variableMatchers, "variableMatchers must not be null");
        variableMatchers = List.copyOf(variableMatchers);
    }

    /**
     * Evaluates the static (non-variable) matchers against a wait state.
     *
     * @param waitState the wait state to test, never {@code null}
     * @return {@code true} if {@code kind}, {@code name}, and every non-{@code null} static
     *         field of this selector match the corresponding field of {@code waitState}
     * @throws NullPointerException if {@code waitState} is {@code null}
     */
    public boolean matchesStatic(WaitState waitState) {
        Objects.requireNonNull(waitState, "waitState must not be null");
        return kind == waitState.kind()
                && name.equals(waitState.name())
                && matchesOrWildcard(processDefinitionKey, waitState.processDefinitionKey())
                && matchesOrWildcard(activityId, waitState.activityId())
                && matchesOrWildcard(businessKey, waitState.businessKey());
    }

    /**
     * Evaluates every variable matcher against a snapshot of process variables.
     *
     * @param variables the variables visible at the wait state, keyed by name; never
     *                  {@code null}
     * @return {@code true} if this selector has no variable matchers, or every one of them
     *         matches {@code variables}
     * @throws NullPointerException if {@code variables} is {@code null}
     */
    public boolean matchesVariables(Map<String, Object> variables) {
        Objects.requireNonNull(variables, "variables must not be null");
        for (VariableMatcher matcher : variableMatchers) {
            if (!matcher.matches(variables)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether this selector needs a variable snapshot to be fully evaluated.
     *
     * @return {@code true} if this selector has at least one variable matcher
     */
    public boolean requiresVariables() {
        return !variableMatchers.isEmpty();
    }

    private static boolean matchesOrWildcard(String selectorValue, String waitStateValue) {
        return selectorValue == null || selectorValue.equals(waitStateValue);
    }
}
