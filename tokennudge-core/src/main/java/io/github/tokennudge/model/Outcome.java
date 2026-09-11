package io.github.tokennudge.model;

/**
 * The result of processing a single observed {@link WaitState} during one loop iteration.
 */
public enum Outcome {

    /** A simulation matched and its action was executed successfully. */
    HANDLED,

    /** No registered simulation matched this wait state; it was left untouched. */
    UNMATCHED,

    /** A simulation matched, but the wait state could not be claimed (raced by another worker). */
    CLAIM_LOST,

    /** A simulation matched and was claimed, but the engine rejected the action. */
    ACTION_FAILED
}
