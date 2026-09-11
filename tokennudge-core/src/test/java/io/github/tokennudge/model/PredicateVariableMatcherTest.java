package io.github.tokennudge.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PredicateVariableMatcherTest {

    @Test
    void matchesWhenConditionIsTrue() {
        var matcher = new PredicateVariableMatcher("amount", value -> ((Integer) value) > 1000);
        assertThat(matcher.matches(Map.of("amount", 4200))).isTrue();
    }

    @Test
    void doesNotMatchWhenConditionIsFalse() {
        var matcher = new PredicateVariableMatcher("amount", value -> ((Integer) value) > 10_000);
        assertThat(matcher.matches(Map.of("amount", 4200))).isFalse();
    }

    @Test
    void missingVariableNeverMatchesConditionIsNotInvoked() {
        var matcher = new PredicateVariableMatcher("amount", value -> {
            throw new AssertionError("should not be invoked for a missing variable");
        });
        assertThat(matcher.matches(Map.of("other", 1))).isFalse();
    }

    @Test
    void conditionReceivesNullWhenVariableValueIsNull() {
        java.util.Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("comment", null);
        var matcher = new PredicateVariableMatcher("comment", value -> value == null);
        assertThat(matcher.matches(withNull)).isTrue();
    }

    @Test
    void nullVariablesMapIsRejected() {
        var matcher = new PredicateVariableMatcher("amount", value -> true);
        assertThatNullPointerException().isThrownBy(() -> matcher.matches(null));
    }

    @Test
    void nullNameIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new PredicateVariableMatcher(null, value -> true));
    }

    @Test
    void nullConditionIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new PredicateVariableMatcher("amount", null));
    }

    @Test
    void describeMentionsVariableName() {
        assertThat(new PredicateVariableMatcher("amount", value -> true).describe()).contains("amount");
    }
}
