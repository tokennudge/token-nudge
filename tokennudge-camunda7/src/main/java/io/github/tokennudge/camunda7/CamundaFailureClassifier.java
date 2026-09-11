package io.github.tokennudge.camunda7;

import io.github.tokennudge.spi.EngineActionException;

/**
 * Classifies an {@link EngineActionException} raised by {@link EngineRestClient} against the
 * handful of engine-rest error shapes documented in {@code docs/PROGRESS.md} ("Known risks"),
 * so that callers (the future {@code Camunda7EngineAdapter}) do not need to re-parse error
 * messages themselves.
 */
final class CamundaFailureClassifier {

    private static final String NOT_FOUND_TYPE = "NotFoundException";
    private static final String REST_EXCEPTION_TYPE = "RestException";
    private static final String DOES_NOT_EXIST = "does not exist";
    private static final String CANNOT_BE_LOCKED = "cannot be locked by worker";

    private CamundaFailureClassifier() {
    }

    /**
     * Classifies a rejected request.
     *
     * @param exception the exception to classify, never {@code null}
     * @return the classification; {@link CamundaFailureClassification#UNCLASSIFIED} if none
     *         of the known shapes apply
     */
    static CamundaFailureClassification classify(EngineActionException exception) {
        return classify(exception.status(), exception.engineErrorType(), exception.engineMessage());
    }

    /**
     * Classifies the raw status/type/message triple directly, before an
     * {@link EngineActionException} exists (used by {@link EngineRestClient} while building
     * the exception's message).
     *
     * @param status  the HTTP status code
     * @param type    the engine's error type, or {@code null}
     * @param message the engine's error message, or {@code null}
     * @return the classification; {@link CamundaFailureClassification#UNCLASSIFIED} if none
     *         of the known shapes apply
     */
    static CamundaFailureClassification classify(int status, String type, String message) {
        if (status == 404 && NOT_FOUND_TYPE.equals(type)) {
            return CamundaFailureClassification.ENDPOINT_NOT_FOUND;
        }
        if (status == 404 && REST_EXCEPTION_TYPE.equals(type) && containsIgnoringNull(message, DOES_NOT_EXIST)) {
            return CamundaFailureClassification.RESOURCE_MISSING;
        }
        if (status == 400 && REST_EXCEPTION_TYPE.equals(type) && containsIgnoringNull(message, CANNOT_BE_LOCKED)) {
            return CamundaFailureClassification.LOCKED_BY_OTHER_WORKER;
        }
        return CamundaFailureClassification.UNCLASSIFIED;
    }

    private static boolean containsIgnoringNull(String message, String fragment) {
        return message != null && message.contains(fragment);
    }

    /**
     * Returns a short, human-readable explanation for a classification, suitable for
     * appending to an exception message, or {@code null} for
     * {@link CamundaFailureClassification#UNCLASSIFIED}.
     *
     * @param classification the classification, never {@code null}
     * @return the description, or {@code null}
     */
    static String describe(CamundaFailureClassification classification) {
        return switch (classification) {
            case ENDPOINT_NOT_FOUND -> "endpoint not found — check the engine-rest base URL";
            case RESOURCE_MISSING -> "the referenced resource no longer exists";
            case LOCKED_BY_OTHER_WORKER -> "locked by another worker";
            case UNCLASSIFIED -> null;
        };
    }
}
