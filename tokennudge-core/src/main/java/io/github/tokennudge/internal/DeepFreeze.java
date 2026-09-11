package io.github.tokennudge.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursively produces a defensive, unmodifiable copy of a value tree made of the types
 * TokenNudge treats as process variables: scalars (assumed immutable, e.g. {@link String},
 * {@link Boolean}, boxed numbers, {@link java.math.BigDecimal},
 * {@link java.time.OffsetDateTime}), the mutable {@link Date} and {@code byte[]} types, and
 * {@link Map}/{@link List} containers of the same.
 *
 * <p>Used by both {@code io.github.tokennudge.Variables} and
 * {@code io.github.tokennudge.model.JournalEntry} so that neither construction nor read
 * access can observe mutation happening on the other side: {@link Date} and {@code byte[]}
 * values are cloned, and {@link Map}/{@link List} containers are rebuilt into new,
 * unmodifiable structures, recursively. Unlike {@link Map#copyOf(Map)} /
 * {@link List#copyOf(java.util.Collection)}, {@code null} values/elements are tolerated,
 * since a process variable may legitimately be {@code null}.
 *
 * <p>Not public API; see the package documentation.
 */
public final class DeepFreeze {

    private DeepFreeze() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Recursively freezes a value.
     *
     * @param value the value to freeze; may be {@code null}, a {@link Map}, a {@link List},
     *              a {@link Date}, a {@code byte[]}, or any other (assumed immutable) type
     * @param <T>   the static type of {@code value}, preserved on the returned copy
     * @return a defensive, recursively unmodifiable copy of {@code value}
     */
    @SuppressWarnings("unchecked")
    public static <T> T freeze(T value) {
        return (T) freezeValue(value);
    }

    private static Object freezeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof Date date) {
            return date.clone();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(entry.getKey(), freezeValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(freezeValue(element));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
