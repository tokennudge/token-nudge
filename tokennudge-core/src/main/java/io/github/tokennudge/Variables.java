package io.github.tokennudge;

import io.github.tokennudge.internal.DeepFreeze;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable, order-preserving set of process variables.
 *
 * <p>Instances wrap a {@code Map<String, Object>} snapshot: constructing a {@link Variables}
 * copies the supplied map, and the map returned by {@link #asMap()} is unmodifiable, so
 * neither the caller nor {@link Variables} itself can observe later mutation from the
 * other side. This holds recursively: mutable leaf values ({@link Date}, {@code byte[]})
 * are cloned, and nested {@link Map}/{@link List} containers are rebuilt into new,
 * unmodifiable structures, both when a {@link Variables} instance is constructed and every
 * time {@link #asMap()} is called.
 *
 * <p>A variable value may be {@code null} (representing a process variable explicitly set
 * to {@code null}). Supported value types are: {@code null}, {@link String}, {@link Boolean},
 * {@link Integer}, {@link Long}, {@link Short}, {@link Double}, {@link Date},
 * {@link OffsetDateTime}, {@code byte[]}, and, for JSON-typed variables, {@link Map} and
 * {@link List} (whose entries/elements must themselves be one of these supported types).
 *
 * @see io.github.tokennudge.model.Action.CompleteExternalTask
 */
public final class Variables {

    private static final Variables EMPTY = new Variables(Map.of());

    private final Map<String, Object> values;

    private Variables(Map<String, Object> values) {
        this.values = values;
    }

    /**
     * Returns an empty {@link Variables} instance.
     *
     * @return an immutable, empty set of variables
     */
    public static Variables empty() {
        return EMPTY;
    }

    /**
     * Creates a {@link Variables} instance from a defensive copy of the given map.
     *
     * @param variables the source map; entries are copied, the map itself is not retained
     * @return an immutable snapshot of {@code variables}
     * @throws NullPointerException     if {@code variables} is {@code null}
     * @throws IllegalArgumentException if a key is {@code null}, or a value is of an
     *                                  unsupported type
     */
    public static Variables of(Map<String, ?> variables) {
        Objects.requireNonNull(variables, "variables must not be null");
        if (variables.isEmpty()) {
            return EMPTY;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : variables.entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                throw new IllegalArgumentException("variable name must not be null");
            }
            Object value = entry.getValue();
            requireSupportedValue(name, value);
            copy.put(name, DeepFreeze.freeze(value));
        }
        return new Variables(Collections.unmodifiableMap(copy));
    }

    private static void requireSupportedValue(String name, Object value) {
        if (!isSupportedValue(value)) {
            throw new IllegalArgumentException(
                    "unsupported value type for variable '" + name + "': "
                            + value.getClass().getName());
        }
    }

    private static boolean isSupportedValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Short
                || value instanceof Double
                || value instanceof Date
                || value instanceof OffsetDateTime
                || value instanceof BigDecimal
                || value instanceof byte[]) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.keySet().stream().allMatch(String.class::isInstance)
                    && map.values().stream().allMatch(Variables::isSupportedValue);
        }
        if (value instanceof List<?> list) {
            return list.stream().allMatch(Variables::isSupportedValue);
        }
        return false;
    }

    /**
     * Returns a defensive, unmodifiable copy of the underlying variable map.
     *
     * <p>Mutable leaf values ({@link Date}, {@code byte[]}) in the returned map are fresh
     * clones, and nested {@link Map}/{@link List} containers are freshly built and
     * unmodifiable, so mutating anything reachable from the returned map (where the runtime
     * type otherwise permits it) never affects this {@link Variables} instance or any
     * previously/subsequently returned map.
     *
     * @return the variables as an unmodifiable, deeply defensive copy, never {@code null}
     */
    public Map<String, Object> asMap() {
        return DeepFreeze.freeze(values);
    }

    /**
     * Returns whether this instance holds no variables.
     *
     * @return {@code true} if there are no variables
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Returns the number of variables.
     *
     * @return the variable count
     */
    public int size() {
        return values.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Variables other)) {
            return false;
        }
        return values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "Variables" + values;
    }
}
