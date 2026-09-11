package io.github.tokennudge.camunda7.dto;

import java.util.Map;

/**
 * A single engine-rest typed value, as used in request/response variable maps:
 * {@code {"value", "type", "valueInfo"}}.
 *
 * @param value     the raw JSON value; its shape depends on {@code type} (for example a
 *                  string, number, boolean, or {@code null})
 * @param type      the engine's type name, for example {@code "String"}, {@code "Integer"},
 *                  {@code "Date"}, {@code "Bytes"}, or {@code "Json"}
 * @param valueInfo additional type-specific metadata (for example an object type name), or
 *                  {@code null}
 */
public record TypedValueDto(Object value, String type, Map<String, Object> valueInfo) {
}
