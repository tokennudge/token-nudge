package io.github.tokennudge.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepFreezeTest {

    @Test
    void nullFreezesToNull() {
        assertThat(DeepFreeze.<Object>freeze(null)).isNull();
    }

    @Test
    void scalarsAreReturnedAsIs() {
        assertThat(DeepFreeze.<Object>freeze("text")).isEqualTo("text");
        assertThat(DeepFreeze.<Object>freeze(42)).isEqualTo(42);
    }

    @Test
    void byteArrayIsCloned() {
        byte[] source = {1, 2, 3};
        byte[] frozen = DeepFreeze.freeze(source);

        assertThat(frozen).isNotSameAs(source).containsExactly(1, 2, 3);

        source[0] = 9;
        assertThat(frozen[0]).isEqualTo((byte) 1);
    }

    @Test
    void dateIsCloned() {
        Date source = new Date(1_000L);
        Date frozen = DeepFreeze.freeze(source);

        assertThat(frozen).isNotSameAs(source).isEqualTo(source);

        source.setTime(2_000L);
        assertThat(frozen.getTime()).isEqualTo(1_000L);
    }

    @Test
    void mapIsRebuiltAndUnmodifiable() {
        Map<String, Object> source = new HashMap<>();
        source.put("amount", 100);
        Map<String, Object> frozen = DeepFreeze.freeze(source);

        source.put("amount", 200);

        assertThat(frozen).containsEntry("amount", 100);
        assertThatThrownBy(() -> frozen.put("other", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mapToleratesNullValues() {
        Map<String, Object> source = new HashMap<>();
        source.put("comment", null);
        assertThat(DeepFreeze.freeze(source)).containsEntry("comment", null);
    }

    @Test
    void listIsRebuiltAndUnmodifiable() {
        List<Object> source = new ArrayList<>(List.of("a", "b"));
        List<Object> frozen = DeepFreeze.freeze(source);

        source.add("c");

        assertThat(frozen).containsExactly("a", "b");
        assertThatThrownBy(() -> frozen.add("d")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void utilityClassCannotBeInstantiated() throws Exception {
        var constructor = DeepFreeze.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                        java.lang.reflect.InvocationTargetException.class, constructor::newInstance)
                .getCause())
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void nestedContainersAndMutableLeavesAreFrozenRecursively() {
        byte[] payload = {1, 2, 3};
        Map<String, Object> nested = new HashMap<>();
        nested.put("payload", payload);
        Map<String, Object> source = new HashMap<>();
        source.put("nested", nested);
        source.put("list", new ArrayList<>(List.of(nested)));

        Map<String, Object> frozen = DeepFreeze.freeze(source);

        payload[0] = 42;

        @SuppressWarnings("unchecked")
        Map<String, Object> frozenNested = (Map<String, Object>) frozen.get("nested");
        assertThat((byte[]) frozenNested.get("payload")).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> frozenNested.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);

        @SuppressWarnings("unchecked")
        List<Object> frozenList = (List<Object>) frozen.get("list");
        assertThatThrownBy(() -> frozenList.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
