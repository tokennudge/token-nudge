package io.github.tokennudge.model;

import io.github.tokennudge.internal.ValueComparison;

import java.util.Map;
import java.util.Objects;

/**
 * A {@link VariableMatcher} that requires a named variable to be equal to an expected
 * value.
 *
 * <p>Equality is evaluated via {@link ValueComparison#equals(Object, Object)}, which
 * normalizes numeric types: an expected value of {@code 4200} (as an {@link Integer})
 * matches an actual value of {@code 4200L} (as a {@link Long}) or a {@link java.math.BigDecimal}
 * of {@code 4200}. A {@code null} expected value requires the actual value to also be
 * {@code null}.
 *
 * @param name          the variable name to match, never {@code null}
 * @param expectedValue the expected value; may be {@code null} to require the variable to
 *                      be present and {@code null}
 */
public record EqualsVariableMatcher(String name, Object expectedValue) implements VariableMatcher {

    /**
     * Validates the variable name.
     *
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public EqualsVariableMatcher {
        Objects.requireNonNull(name, "name must not be null");
    }

    @Override
    public boolean matches(Map<String, Object> vars) {
        Objects.requireNonNull(vars, "vars must not be null");
        if (!vars.containsKey(name)) {
            return false;
        }
        return ValueComparison.equals(expectedValue, vars.get(name));
    }

    @Override
    public String describe() {
        return name + " == " + expectedValue;
    }
}
