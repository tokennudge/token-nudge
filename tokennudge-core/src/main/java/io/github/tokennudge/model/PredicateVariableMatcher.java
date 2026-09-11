package io.github.tokennudge.model;

import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A {@link VariableMatcher} that requires a named variable to satisfy an arbitrary
 * {@link Predicate}.
 *
 * <p>The predicate is only invoked when the named variable is present; a missing variable
 * never matches, regardless of the predicate.
 *
 * @param name      the variable name to match, never {@code null}
 * @param condition the condition the variable's value must satisfy, never {@code null};
 *                  invoked with {@code null} if the variable's value is {@code null}
 */
public record PredicateVariableMatcher(String name, Predicate<Object> condition) implements VariableMatcher {

    /**
     * Validates the variable name and condition.
     *
     * @throws NullPointerException if {@code name} or {@code condition} is {@code null}
     */
    public PredicateVariableMatcher {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
    }

    @Override
    public boolean matches(Map<String, Object> vars) {
        Objects.requireNonNull(vars, "vars must not be null");
        if (!vars.containsKey(name)) {
            return false;
        }
        return condition.test(vars.get(name));
    }

    @Override
    public String describe() {
        return name + " matches custom predicate";
    }
}
