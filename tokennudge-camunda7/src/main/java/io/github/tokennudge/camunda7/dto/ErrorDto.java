package io.github.tokennudge.camunda7.dto;

/**
 * The engine-rest error body returned with most non-2xx responses:
 * {@code {"type", "message", "code"}}.
 *
 * @param type    the engine's exception class name, for example {@code "RestException"} or
 *                {@code "NotFoundException"}; may be {@code null} if the body did not
 *                include one
 * @param message the human-readable error message; may be {@code null}
 * @param code    an engine-specific error code, or {@code null} if none was reported
 */
public record ErrorDto(String type, String message, Integer code) {
}
