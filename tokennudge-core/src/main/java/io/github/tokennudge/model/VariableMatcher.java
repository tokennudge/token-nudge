package io.github.tokennudge.model;

import java.util.Map;

/**
 * Matches a single named process variable against some condition.
 *
 * <p>Implementations are immutable value types. A matcher only matches when the named
 * variable is present in the supplied map (a missing variable never matches, regardless
 * of the condition).
 *
 * @see EqualsVariableMatcher
 * @see PredicateVariableMatcher
 */
public interface VariableMatcher {

    /**
     * Returns the name of the variable this matcher applies to.
     *
     * @return the variable name, never {@code null}
     */
    String name();

    /**
     * Evaluates this matcher against a snapshot of process variables.
     *
     * @param vars the variables visible at the wait state, keyed by name; never
     *             {@code null}
     * @return {@code true} if the named variable is present and satisfies this matcher's
     *         condition
     */
    boolean matches(Map<String, Object> vars);

    /**
     * Returns a short, human-readable description of this matcher, used in diagnostic
     * messages (for example near-miss reporting in verification failures).
     *
     * @return a description, never {@code null}
     */
    String describe();
}
