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
    private static final String CANNOT_FIND_TASK = "Cannot find task with id";
    private static final String NO_EXECUTION_MATCHES = "No process definition or execution matches the parameters";

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
        if (!REST_EXCEPTION_TYPE.equals(type)) {
            return CamundaFailureClassification.UNCLASSIFIED;
        }
        // External-task lock/complete: a 404 "does not exist" for a completed or unknown id
        // (see docs/PROGRESS.md "Known risks").
        if (status == 404 && containsIgnoringNull(message, DOES_NOT_EXIST)) {
            return CamundaFailureClassification.RESOURCE_MISSING;
        }
        // User-task completion of an already-completed or unknown task id: unlike the
        // external-task endpoints, this comes back as a 500, not a 404, wrapping
        // org.camunda.bpm.engine.impl.persistence.entity.TaskManager's own "task is null"
        // check (confirmed identical shape on Camunda 7.24.0 and CIB Seven 2.2.0).
        if (status == 500 && containsIgnoringNull(message, CANNOT_FIND_TASK)) {
            return CamundaFailureClassification.RESOURCE_MISSING;
        }
        // Message correlation targeting an already-consumed subscription or an ended process
        // instance: the engine reports no matching execution at all, distinct from the
        // "matches more than one execution" ambiguous-correlation case (left UNCLASSIFIED,
        // since that is a definite, different rejection, not a benign race). Confirmed
        // identical shape (modulo the org.camunda/org.cibseven package prefix, not matched
        // here) on both engines.
        if (status == 400 && containsIgnoringNull(message, NO_EXECUTION_MATCHES)) {
            return CamundaFailureClassification.RESOURCE_MISSING;
        }
        if (status == 400 && containsIgnoringNull(message, CANNOT_BE_LOCKED)) {
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
