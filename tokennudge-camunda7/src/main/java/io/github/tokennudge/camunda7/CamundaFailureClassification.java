package io.github.tokennudge.camunda7;

/**
 * A machine-readable classification of a rejected engine-rest request, derived from its HTTP
 * status and Camunda error body ({@code type}/{@code message}). See
 * {@link CamundaFailureClassifier}.
 */
enum CamundaFailureClassification {

    /**
     * The request hit a route that does not exist on this engine (a {@code 404} with the
     * generic {@code NotFoundException} type, not a specific "resource does not exist"
     * message). Usually a wrong engine-rest base URL, not a business-level failure.
     */
    ENDPOINT_NOT_FOUND,

    /**
     * The targeted resource (an external task, a user task, or a message subscription's
     * process instance) no longer exists or never did &mdash; it may have already been
     * completed, correlated, or deleted by another worker, a human, or the process itself.
     * Covers three distinct engine-rest shapes, confirmed identical on Camunda 7.24.0 and CIB
     * Seven 2.2.0: a {@code 404} "does not exist" (external task), a {@code 500} "Cannot find
     * task with id ..." (user task completion), and a {@code 400} "No process definition or
     * execution matches the parameters" (message correlation).
     */
    RESOURCE_MISSING,

    /**
     * An external task lock attempt was rejected because another worker already holds the
     * lock.
     */
    LOCKED_BY_OTHER_WORKER,

    /**
     * No specific classification applies; the caller should fall back to the exception's
     * message.
     */
    UNCLASSIFIED
}
