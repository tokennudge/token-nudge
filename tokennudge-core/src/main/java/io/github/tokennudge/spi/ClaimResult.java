package io.github.tokennudge.spi;

/**
 * The result of attempting to {@link EngineAdapter#claim(io.github.tokennudge.model.WaitState)}
 * a wait state.
 */
public enum ClaimResult {

    /** The wait state was successfully claimed by this worker. */
    CLAIMED,

    /**
     * The wait state could no longer be claimed, typically because another worker (or a
     * real production worker) claimed or completed it first. This is an expected race, not
     * an error.
     */
    LOST
}
