package io.github.tokennudge.camunda7;

import io.github.tokennudge.camunda7.dto.ErrorDto;
import io.github.tokennudge.spi.EngineActionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CamundaFailureClassifierTest {

    @Test
    void parsesErrorDtoFromJson() throws Exception {
        ErrorDto error = JsonSupport.MAPPER.readValue(
                "{\"type\":\"RestException\",\"message\":\"boom\",\"code\":0}", ErrorDto.class);
        assertThat(error.type()).isEqualTo("RestException");
        assertThat(error.message()).isEqualTo("boom");
        assertThat(error.code()).isZero();
    }

    @Test
    void parsesErrorDtoWithNullCode() throws Exception {
        ErrorDto error = JsonSupport.MAPPER.readValue(
                "{\"type\":\"NotFoundException\",\"message\":\"HTTP 404 Not Found\",\"code\":null}", ErrorDto.class);
        assertThat(error.code()).isNull();
    }

    @Test
    void classifiesUnknownRouteAsEndpointNotFound() {
        EngineActionException e = new EngineActionException("x", 404, "NotFoundException", "HTTP 404 Not Found");
        assertThat(CamundaFailureClassifier.classify(e)).isEqualTo(CamundaFailureClassification.ENDPOINT_NOT_FOUND);
        assertThat(CamundaFailureClassifier.describe(CamundaFailureClassification.ENDPOINT_NOT_FOUND))
                .contains("engine-rest base URL");
    }

    @Test
    void classifiesMissingResourceAsResourceMissing() {
        EngineActionException e = new EngineActionException(
                "x", 404, "RestException", "External task with id abc does not exist");
        assertThat(CamundaFailureClassifier.classify(e)).isEqualTo(CamundaFailureClassification.RESOURCE_MISSING);
    }

    @Test
    void classifiesLockConflictAsLockedByOtherWorker() {
        EngineActionException e = new EngineActionException(
                "x", 400, "RestException",
                "External Task abc cannot be locked by worker 'worker-B'. It is locked by worker 'worker-A'.");
        assertThat(CamundaFailureClassifier.classify(e))
                .isEqualTo(CamundaFailureClassification.LOCKED_BY_OTHER_WORKER);
    }

    @Test
    void unrecognizedShapeIsUnclassified() {
        EngineActionException e = new EngineActionException("x", 500, "SomeOtherException", "unexpected");
        assertThat(CamundaFailureClassifier.classify(e)).isEqualTo(CamundaFailureClassification.UNCLASSIFIED);
        assertThat(CamundaFailureClassifier.describe(CamundaFailureClassification.UNCLASSIFIED)).isNull();
    }

    @Test
    void nullEngineMessageDoesNotMatchTextFragments() {
        EngineActionException e = new EngineActionException("x", 404, "RestException", null);
        assertThat(CamundaFailureClassifier.classify(e)).isEqualTo(CamundaFailureClassification.UNCLASSIFIED);
    }
}
