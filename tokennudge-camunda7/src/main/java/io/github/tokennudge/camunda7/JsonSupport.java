package io.github.tokennudge.camunda7;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single {@link ObjectMapper} instance shared by this module's REST client, variable
 * codec, and DTOs.
 *
 * <p>Configured to ignore properties the engine may add in newer versions
 * ({@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} disabled) and to omit
 * {@code null} fields when serializing request bodies ({@link JsonInclude.Include#NON_NULL}).
 */
final class JsonSupport {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

    private JsonSupport() {
    }
}
