package io.github.tokennudge.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ValueComparisonTest {

    @Test
    void bothNullAreEqual() {
        assertThat(ValueComparison.equals(null, null)).isTrue();
    }

    @Test
    void nullIsNeverEqualToNonNull() {
        assertThat(ValueComparison.equals(null, "x")).isFalse();
        assertThat(ValueComparison.equals("x", null)).isFalse();
    }

    @Test
    void integerEqualsLongOfSameNumericValue() {
        assertThat(ValueComparison.equals(4200, 4200L)).isTrue();
        assertThat(ValueComparison.equals(4200L, 4200)).isTrue();
    }

    @Test
    void integerNotEqualsLongOfDifferentNumericValue() {
        assertThat(ValueComparison.equals(4200, 4201L)).isFalse();
    }

    @Test
    void bigDecimalComparedByNumericValueNotScale() {
        assertThat(ValueComparison.equals(new BigDecimal("4200"), new BigDecimal("4200.00"))).isTrue();
        assertThat(ValueComparison.equals(new BigDecimal("4200.00"), 4200)).isTrue();
        assertThat(ValueComparison.equals(4200L, new BigDecimal("4200"))).isTrue();
    }

    @Test
    void bigIntegerComparedNumerically() {
        assertThat(ValueComparison.equals(BigInteger.valueOf(4200), 4200)).isTrue();
        assertThat(ValueComparison.equals(BigInteger.valueOf(4200), new BigDecimal("4200.0"))).isTrue();
    }

    @ParameterizedTest
    @MethodSource("numericPairs")
    void allNumericTypeCombinationsNormalizeCorrectly(Object expected, Object actual, boolean expectedEqual) {
        assertThat(ValueComparison.equals(expected, actual)).isEqualTo(expectedEqual);
    }

    static Stream<Arguments> numericPairs() {
        return Stream.of(
                Arguments.of((byte) 42, (short) 42, true),
                Arguments.of((short) 42, 42, true),
                Arguments.of(42, 42L, true),
                Arguments.of(42L, 42.0d, true),
                Arguments.of(42.0f, 42.0d, true),
                Arguments.of(42, 43, false),
                Arguments.of(42.5d, 42, false));
    }

    @Test
    void nonNumericValuesFallBackToObjectEquals() {
        assertThat(ValueComparison.equals("paid", "paid")).isTrue();
        assertThat(ValueComparison.equals("paid", "PAID")).isFalse();
        assertThat(ValueComparison.equals(true, true)).isTrue();
        assertThat(ValueComparison.equals(true, false)).isFalse();
    }

    @Test
    void numberIsNeverEqualToNonNumericValueOfSameTextualForm() {
        assertThat(ValueComparison.equals(4200, "4200")).isFalse();
    }

    @Test
    void byteArraysAreComparedByContentNotReference() {
        byte[] expected = {1, 2, 3};
        byte[] actualEqualContent = {1, 2, 3};
        byte[] actualDifferentContent = {1, 2, 4};

        assertThat(ValueComparison.equals(expected, actualEqualContent)).isTrue();
        assertThat(ValueComparison.equals(expected, actualDifferentContent)).isFalse();
        assertThat(ValueComparison.equals(expected, expected.clone())).isTrue();
    }

    @Test
    void byteArraysOfDifferentLengthAreNotEqual() {
        assertThat(ValueComparison.equals(new byte[] {1, 2}, new byte[] {1, 2, 3})).isFalse();
    }

    @Test
    void listsAreComparedRecursivelyWithNumericNormalizationAndByteArrayContent() {
        List<Object> expected = List.of(4200, "EUR", new byte[] {1, 2, 3});
        List<Object> actualSameValues = List.of(4200L, "EUR", new byte[] {1, 2, 3});
        List<Object> actualDifferentValues = List.of(4201L, "EUR", new byte[] {1, 2, 3});

        assertThat(ValueComparison.equals(expected, actualSameValues)).isTrue();
        assertThat(ValueComparison.equals(expected, actualDifferentValues)).isFalse();
    }

    @Test
    void listsOfDifferentSizeAreNotEqual() {
        assertThat(ValueComparison.equals(List.of(1, 2), List.of(1, 2, 3))).isFalse();
    }

    @Test
    void mapsAreComparedRecursivelyWithNumericNormalizationAndByteArrayContent() {
        Map<String, Object> expected = Map.of("amount", 4200, "payload", new byte[] {9, 8, 7});
        Map<String, Object> actualSameValues = Map.of("amount", 4200L, "payload", new byte[] {9, 8, 7});
        Map<String, Object> actualDifferentValues = Map.of("amount", 4200L, "payload", new byte[] {9, 8, 0});

        assertThat(ValueComparison.equals(expected, actualSameValues)).isTrue();
        assertThat(ValueComparison.equals(expected, actualDifferentValues)).isFalse();
    }

    @Test
    void mapsWithDifferentKeySetsAreNotEqual() {
        assertThat(ValueComparison.equals(Map.of("a", 1), Map.of("b", 1))).isFalse();
        assertThat(ValueComparison.equals(Map.of("a", 1), Map.of("a", 1, "b", 2))).isFalse();
    }

    @Test
    void mapIsNeverEqualToListOrScalar() {
        assertThat(ValueComparison.equals(Map.of("a", 1), List.of(1))).isFalse();
        assertThat(ValueComparison.equals(Map.of("a", 1), "a")).isFalse();
        assertThat(ValueComparison.equals(List.of(1), "1")).isFalse();
    }

    @Test
    void utilityClassCannotBeInstantiated() throws Exception {
        var constructor = ValueComparison.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                java.lang.reflect.InvocationTargetException.class, constructor::newInstance)
                .getCause()).isInstanceOf(AssertionError.class);
    }
}
