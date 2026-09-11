package io.github.tokennudge.camunda7;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.tokennudge.camunda7.dto.TypedValueDto;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Converts between Java variable values (as accepted by {@code io.github.tokennudge.Variables})
 * and engine-rest's typed-value JSON shape ({@code {"value", "type", "valueInfo"}}).
 *
 * <p>Supported types: {@code null} ({@code "Null"}), {@link String}, {@link Boolean},
 * {@link Integer}, {@link Long}, {@link Short}, {@link Double}, {@link Date} and
 * {@link OffsetDateTime} (both encoded as {@code "Date"}, formatted
 * {@code yyyy-MM-dd'T'HH:mm:ss.SSSZ}), {@code byte[]} ({@code "Bytes"}, base64), and
 * {@link Map}/{@link List} ({@code "Json"}, serialized with Jackson).
 *
 * <p>A non-finite {@link Double} ({@link Double#isNaN()} or {@link Double#isInfinite()}) is
 * rejected by {@link #encode(Object)} with {@link IllegalArgumentException}: engine-rest has
 * no representation for it as a Camunda {@code Double} variable, and silently encoding it as
 * a JSON string (which is what plain Jackson serialization would otherwise do) would produce
 * a value typed {@code Double} whose JSON shape does not match that type.
 *
 * <p>{@link java.math.BigDecimal} is deliberately <strong>not</strong> supported by
 * {@link #encode(Object)}, even though {@code io.github.tokennudge.Variables} accepts it (for
 * numeric-normalized equality comparisons in matchers/verifications, never sent to an
 * engine). Rather than silently narrowing it to {@code Double} (lossy for values outside
 * {@code double}'s precision or range) or {@code String} (changes the variable's engine type
 * from what a caller might expect), encoding a {@link java.math.BigDecimal} fails fast with a
 * clear {@link IllegalArgumentException}, consistent with any other unsupported type.
 */
final class VariableCodec {

    private static final String DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

    private VariableCodec() {
    }

    /**
     * Encodes a Java value as a typed value.
     *
     * @param value the value to encode; may be {@code null}
     * @return the encoded typed value
     * @throws IllegalArgumentException if {@code value} is of an unsupported type
     */
    static TypedValueDto encode(Object value) {
        if (value == null) {
            return new TypedValueDto(null, "Null", null);
        }
        if (value instanceof String s) {
            return new TypedValueDto(s, "String", null);
        }
        if (value instanceof Boolean b) {
            return new TypedValueDto(b, "Boolean", null);
        }
        if (value instanceof Integer i) {
            return new TypedValueDto(i, "Integer", null);
        }
        if (value instanceof Long l) {
            return new TypedValueDto(l, "Long", null);
        }
        if (value instanceof Short sh) {
            return new TypedValueDto(sh, "Short", null);
        }
        if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalArgumentException(
                        "unsupported Double variable value (NaN/Infinity are not representable as a "
                                + "Camunda Double variable): " + d);
            }
            return new TypedValueDto(d, "Double", null);
        }
        if (value instanceof BigDecimal bd) {
            throw new IllegalArgumentException(
                    "unsupported variable value type: " + BigDecimal.class.getName() + "; convert to Double or "
                            + "String before sending it to the engine: " + bd);
        }
        if (value instanceof Date date) {
            return new TypedValueDto(formatDate(date), "Date", null);
        }
        if (value instanceof OffsetDateTime odt) {
            return new TypedValueDto(formatDate(Date.from(odt.toInstant())), "Date", null);
        }
        if (value instanceof byte[] bytes) {
            return new TypedValueDto(Base64.getEncoder().encodeToString(bytes), "Bytes", null);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return new TypedValueDto(writeJson(value), "Json", null);
        }
        throw new IllegalArgumentException("unsupported variable value type: " + value.getClass().getName());
    }

    /**
     * Decodes a typed value back into a Java value.
     *
     * @param dto the typed value to decode, never {@code null}
     * @return the decoded Java value; for an unrecognized {@link TypedValueDto#type()}, the
     *         raw {@link TypedValueDto#value()} is returned unchanged (documented fallback)
     */
    static Object decode(TypedValueDto dto) {
        return decode(dto.type(), dto.value());
    }

    /**
     * Decodes a value given its engine-rest type name, as used by both
     * {@link TypedValueDto} and the flattened {@code VariableInstanceDto} shape.
     *
     * @param type  the engine-rest type name, may be {@code null}
     * @param value the raw JSON value, may be {@code null}
     * @return the decoded Java value; for {@code null} or an unrecognized {@code type}, the
     *         raw {@code value} is returned unchanged (documented fallback)
     */
    static Object decode(String type, Object value) {
        if (type == null) {
            return value;
        }
        return switch (type) {
            case "Null" -> null;
            case "String" -> value == null ? null : value.toString();
            case "Boolean" -> value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
            case "Integer" -> toNumber(value).intValue();
            case "Long" -> toNumber(value).longValue();
            case "Short" -> toNumber(value).shortValue();
            case "Double" -> toNumber(value).doubleValue();
            case "Date" -> parseDate((String) value);
            case "Bytes" -> value == null ? null : Base64.getDecoder().decode((String) value);
            case "Json" -> readJson((String) value);
            default -> value;
        };
    }

    private static Number toNumber(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static String formatDate(Date date) {
        return new SimpleDateFormat(DATE_PATTERN).format(date);
    }

    private static Date parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new SimpleDateFormat(DATE_PATTERN).parse(value);
        } catch (ParseException e) {
            throw new IllegalArgumentException("could not parse engine date value: " + value, e);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JsonSupport.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("could not encode variable value as JSON: " + value, e);
        }
    }

    private static Object readJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JsonSupport.MAPPER.readValue(json, Object.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("could not parse engine JSON variable value: " + json, e);
        }
    }
}
