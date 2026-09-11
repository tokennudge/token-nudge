package io.github.tokennudge.camunda7;

import io.github.tokennudge.camunda7.dto.TypedValueDto;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VariableCodecTest {

    @Test
    void roundTripsNull() {
        TypedValueDto encoded = VariableCodec.encode(null);
        assertThat(encoded.type()).isEqualTo("Null");
        assertThat(encoded.value()).isNull();
        assertThat(VariableCodec.decode(encoded)).isNull();
    }

    @Test
    void roundTripsString() {
        assertRoundTrip("hello", "String");
    }

    @Test
    void roundTripsBoolean() {
        assertRoundTrip(true, "Boolean");
    }

    @Test
    void roundTripsInteger() {
        assertRoundTrip(4200, "Integer");
    }

    @Test
    void roundTripsLong() {
        assertRoundTrip(4200L, "Long");
    }

    @Test
    void roundTripsShort() {
        assertRoundTrip((short) 42, "Short");
    }

    @Test
    void roundTripsDouble() {
        assertRoundTrip(3.14, "Double");
    }

    @Test
    void roundTripsDate() {
        Date date = new Date(1_700_000_000_123L);
        TypedValueDto encoded = VariableCodec.encode(date);
        assertThat(encoded.type()).isEqualTo("Date");
        assertThat(encoded.value()).isInstanceOf(String.class);
        Object decoded = VariableCodec.decode(encoded);
        assertThat(decoded).isEqualTo(date);
    }

    @Test
    void encodesOffsetDateTimeAsDate() {
        OffsetDateTime odt = OffsetDateTime.of(2026, 9, 11, 12, 30, 0, 0, ZoneOffset.UTC);
        TypedValueDto encoded = VariableCodec.encode(odt);
        assertThat(encoded.type()).isEqualTo("Date");
        Object decoded = VariableCodec.decode(encoded);
        assertThat(decoded).isEqualTo(Date.from(odt.toInstant()));
    }

    @Test
    void roundTripsBytes() {
        byte[] bytes = {1, 2, 3, 4, 5};
        TypedValueDto encoded = VariableCodec.encode(bytes);
        assertThat(encoded.type()).isEqualTo("Bytes");
        assertThat(encoded.value()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
        Object decoded = VariableCodec.decode(encoded);
        assertThat(decoded).isEqualTo(bytes);
    }

    @Test
    void roundTripsJsonMap() {
        Map<String, Object> map = Map.of("a", 1, "b", "two");
        TypedValueDto encoded = VariableCodec.encode(map);
        assertThat(encoded.type()).isEqualTo("Json");
        assertThat(encoded.value()).isInstanceOf(String.class);
        Object decoded = VariableCodec.decode(encoded);
        assertThat(decoded).isInstanceOf(Map.class).isEqualTo(map);
    }

    @Test
    void roundTripsJsonList() {
        List<Object> list = List.of(1, "two", true);
        TypedValueDto encoded = VariableCodec.encode(list);
        assertThat(encoded.type()).isEqualTo("Json");
        Object decoded = VariableCodec.decode(encoded);
        assertThat(decoded).isEqualTo(list);
    }

    @Test
    void unknownTypeFallsBackToRawValue() {
        Object decoded = VariableCodec.decode("SomeFutureType", "raw-value");
        assertThat(decoded).isEqualTo("raw-value");
    }

    @Test
    void nullTypeFallsBackToRawValue() {
        Object decoded = VariableCodec.decode(null, 42);
        assertThat(decoded).isEqualTo(42);
    }

    @Test
    void encodeRejectsUnsupportedType() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VariableCodec.encode(3.14f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRoundTrip(Object value, String expectedType) {
        TypedValueDto encoded = VariableCodec.encode(value);
        assertThat(encoded.type()).isEqualTo(expectedType);
        assertThat(encoded.value()).isEqualTo(value);
        assertThat(VariableCodec.decode(encoded)).isEqualTo(value);
    }
}
