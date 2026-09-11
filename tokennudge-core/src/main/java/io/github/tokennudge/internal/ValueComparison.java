package io.github.tokennudge.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Value equality used by variable matchers, with numeric normalization so that, for
 * example, an {@code Integer} value of {@code 4200} is considered equal to a {@code Long}
 * value of {@code 4200}, or a {@code BigDecimal} of {@code 4200.00}.
 *
 * <p>Not public API; see the package documentation.
 */
public final class ValueComparison {

    private ValueComparison() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Compares two variable values for equality, normalizing numeric types and array
     * content.
     *
     * <p>Both arguments may be {@code null}; two {@code null} values are equal, and a
     * {@code null} is never equal to a non-{@code null} value. When both values are one of
     * the standard boxed numeric types ({@link Byte}, {@link Short}, {@link Integer},
     * {@link Long}, {@link Float}, {@link Double}, {@link BigInteger}, {@link BigDecimal}),
     * they are compared numerically (via {@link BigDecimal#compareTo(BigDecimal)}) rather
     * than requiring identical boxed types. When both values are {@code byte[]}, they are
     * compared by content (via {@link Arrays#equals(byte[], byte[])}) rather than by
     * reference, since plain {@link Object#equals(Object)} would otherwise never consider
     * two distinct array instances equal.
     *
     * <p>When both values are a {@link Map} or both are a {@link List} (the JSON-typed
     * variable containers), their entries/elements are compared recursively with these same
     * rules, so a numeric or {@code byte[]} value nested inside a JSON variable is still
     * normalized. A {@link Map} and a {@link List} are never equal to each other, and
     * neither is ever equal to a scalar. For any other combination of types, equality falls
     * back to {@link Object#equals(Object)}.
     *
     * @param expected the expected value, may be {@code null}
     * @param actual   the actual value, may be {@code null}
     * @return {@code true} if the values are considered equal
     */
    public static boolean equals(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected instanceof byte[] expectedBytes && actual instanceof byte[] actualBytes) {
            return Arrays.equals(expectedBytes, actualBytes);
        }
        if (isNumber(expected) && isNumber(actual)) {
            return toBigDecimal((Number) expected).compareTo(toBigDecimal((Number) actual)) == 0;
        }
        if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            return mapsEqual(expectedMap, actualMap);
        }
        if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
            return listsEqual(expectedList, actualList);
        }
        return expected.equals(actual);
    }

    private static boolean mapsEqual(Map<?, ?> expected, Map<?, ?> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (Map.Entry<?, ?> entry : expected.entrySet()) {
            if (!actual.containsKey(entry.getKey())) {
                return false;
            }
            if (!equals(entry.getValue(), actual.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean listsEqual(List<?> expected, List<?> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!equals(expected.get(i), actual.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumber(Object value) {
        return value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal;
    }

    private static BigDecimal toBigDecimal(Number number) {
        if (number instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (number instanceof BigInteger bigInteger) {
            return new BigDecimal(bigInteger);
        }
        if (number instanceof Float || number instanceof Double) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.valueOf(number.longValue());
    }
}
