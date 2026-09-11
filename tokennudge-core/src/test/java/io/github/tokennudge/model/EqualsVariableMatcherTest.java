package io.github.tokennudge.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class EqualsVariableMatcherTest {

    @Test
    void matchesEqualValue() {
        var matcher = new EqualsVariableMatcher("amount", 4200);
        assertThat(matcher.matches(Map.of("amount", 4200))).isTrue();
    }

    @Test
    void doesNotMatchDifferentValue() {
        var matcher = new EqualsVariableMatcher("amount", 4200);
        assertThat(matcher.matches(Map.of("amount", 4201))).isFalse();
    }

    @Test
    void matchesAcrossNumericTypesViaValueComparison() {
        var matcher = new EqualsVariableMatcher("amount", 4200);
        assertThat(matcher.matches(Map.of("amount", 4200L))).isTrue();
        assertThat(matcher.matches(Map.of("amount", new java.math.BigDecimal("4200.00")))).isTrue();
    }

    @Test
    void missingVariableNeverMatches() {
        var matcher = new EqualsVariableMatcher("amount", 4200);
        assertThat(matcher.matches(Map.of("other", 1))).isFalse();
    }

    @Test
    void nullExpectedValueRequiresPresentNullVariable() {
        var matcher = new EqualsVariableMatcher("comment", null);

        Map<String, Object> withNull = new HashMap<>();
        withNull.put("comment", null);
        assertThat(matcher.matches(withNull)).isTrue();

        assertThat(matcher.matches(Map.of("comment", "not null"))).isFalse();
        assertThat(matcher.matches(Map.of())).isFalse();
    }

    @Test
    void nullVariablesMapIsRejected() {
        var matcher = new EqualsVariableMatcher("amount", 4200);
        assertThatNullPointerException().isThrownBy(() -> matcher.matches(null));
    }

    @Test
    void nullNameIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new EqualsVariableMatcher(null, 1));
    }

    @Test
    void nameAccessorReturnsGivenName() {
        assertThat(new EqualsVariableMatcher("amount", 4200).name()).isEqualTo("amount");
    }

    @Test
    void describeIncludesNameAndExpectedValue() {
        assertThat(new EqualsVariableMatcher("amount", 4200).describe()).contains("amount").contains("4200");
    }
}
