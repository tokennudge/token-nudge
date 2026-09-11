package io.github.tokennudge;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariablesTest {

    @Test
    void emptyIsEmpty() {
        assertThat(Variables.empty().isEmpty()).isTrue();
        assertThat(Variables.empty().size()).isZero();
        assertThat(Variables.empty().asMap()).isEmpty();
    }

    @Test
    void ofEmptyMapReturnsEmptyInstance() {
        assertThat(Variables.of(Map.of())).isEqualTo(Variables.empty());
    }

    @Test
    void ofNullMapThrows() {
        assertThatNullPointerException().isThrownBy(() -> Variables.of(null));
    }

    @Test
    void ofNullKeyThrows() {
        Map<String, Object> withNullKey = new HashMap<>();
        withNullKey.put(null, "value");
        assertThatIllegalArgumentException().isThrownBy(() -> Variables.of(withNullKey));
    }

    @Test
    void nullValuesAreAllowed() {
        Map<String, Object> source = new HashMap<>();
        source.put("comment", null);
        Variables variables = Variables.of(source);
        assertThat(variables.asMap()).containsEntry("comment", null);
        assertThat(variables.size()).isEqualTo(1);
    }

    @Test
    void supportedScalarTypesAreAccepted() {
        Map<String, Object> source = new HashMap<>();
        source.put("s", "text");
        source.put("bool", true);
        source.put("i", 1);
        source.put("l", 1L);
        source.put("sh", (short) 1);
        source.put("d", 1.5d);
        source.put("bd", new BigDecimal("1.50"));
        source.put("date", new Date());
        source.put("odt", OffsetDateTime.now());
        source.put("bytes", new byte[] {1, 2, 3});

        Variables variables = Variables.of(source);

        assertThat(variables.size()).isEqualTo(source.size());
    }

    @Test
    void supportedJsonContainersWithValidElementsAreAccepted() {
        Map<String, Object> nested = Map.of("nestedKey", "nestedValue");
        List<Object> list = List.of("a", 1, true);
        Map<String, Object> source = new HashMap<>();
        source.put("json", nested);
        source.put("list", list);

        Variables variables = Variables.of(source);

        assertThat(variables.asMap().get("json")).isEqualTo(nested);
        assertThat(variables.asMap().get("list")).isEqualTo(list);
    }

    @Test
    void unsupportedValueTypeIsRejected() {
        Map<String, Object> source = new HashMap<>();
        source.put("bad", new Object());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Variables.of(source))
                .withMessageContaining("bad");
    }

    @Test
    void unsupportedValueNestedInListIsRejected() {
        Map<String, Object> source = new HashMap<>();
        source.put("list", List.of(new Object()));
        assertThatIllegalArgumentException().isThrownBy(() -> Variables.of(source));
    }

    @Test
    void unsupportedValueNestedInMapIsRejected() {
        Map<String, Object> source = new HashMap<>();
        source.put("map", Map.of("k", new Object()));
        assertThatIllegalArgumentException().isThrownBy(() -> Variables.of(source));
    }

    @Test
    void constructionMakesADefensiveCopyOfTheSourceMap() {
        Map<String, Object> source = new HashMap<>();
        source.put("amount", 100);
        Variables variables = Variables.of(source);

        source.put("amount", 200);
        source.put("extra", "value");

        assertThat(variables.asMap()).containsExactly(Map.entry("amount", 100));
    }

    @Test
    void asMapIsUnmodifiable() {
        Variables variables = Variables.of(Map.of("amount", 100));

        assertThatThrownBy(() -> variables.asMap().put("other", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equalsAndHashCodeAreBasedOnContents() {
        Variables a = Variables.of(Map.of("amount", 100));
        Variables b = Variables.of(Map.of("amount", 100));
        Variables c = Variables.of(Map.of("amount", 200));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void equalsReturnsFalseForNullAndForNonVariablesArgument() {
        Variables variables = Variables.of(Map.of("amount", 100));

        assertThat(variables.equals(null)).isFalse();
        assertThat(variables.equals("amount=100")).isFalse();
    }

    @Test
    void constructionDeepCopiesNestedMapsAndLists() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("currency", "EUR");
        List<Object> list = new ArrayList<>(List.of("a", "b"));

        Map<String, Object> source = new HashMap<>();
        source.put("nested", nested);
        source.put("list", list);
        Variables variables = Variables.of(source);

        nested.put("currency", "USD");
        list.add("c");

        @SuppressWarnings("unchecked")
        Map<String, Object> storedNested = (Map<String, Object>) variables.asMap().get("nested");
        assertThat(storedNested).containsEntry("currency", "EUR");

        @SuppressWarnings("unchecked")
        List<Object> storedList = (List<Object>) variables.asMap().get("list");
        assertThat(storedList).containsExactly("a", "b");
    }

    @Test
    void asMapNestedContainersAreUnmodifiable() {
        Variables variables = Variables.of(Map.of("nested", Map.of("currency", "EUR")));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) variables.asMap().get("nested");
        assertThatThrownBy(() -> nested.put("currency", "USD"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void byteArrayValueIsClonedOnConstructionAndOnEveryAsMapCall() {
        byte[] source = {1, 2, 3};
        Map<String, Object> map = new HashMap<>();
        map.put("payload", source);
        Variables variables = Variables.of(map);

        source[0] = 42;
        byte[] firstRead = (byte[]) variables.asMap().get("payload");
        assertThat(firstRead).containsExactly(1, 2, 3);

        firstRead[0] = 99;
        byte[] secondRead = (byte[]) variables.asMap().get("payload");
        assertThat(secondRead).containsExactly(1, 2, 3);
    }

    @Test
    void toStringContainsContents() {
        Variables variables = Variables.of(Map.of("amount", 100));
        assertThat(variables.toString()).contains("amount").contains("100");
    }
}
