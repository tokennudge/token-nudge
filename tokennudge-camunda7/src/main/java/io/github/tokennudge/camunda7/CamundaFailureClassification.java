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
     * The targeted resource (for example an external task id) no longer exists or never
     * did &mdash; it may have already been completed or deleted by another worker.
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
