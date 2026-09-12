package io.github.tokennudge.spi;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.WaitState;

import java.util.List;
import java.util.Map;

/**
 * The engine port: transport-specific access to a BPMN engine, implemented by adapter
 * modules such as {@code tokennudge-camunda7}.
 *
 * <p>Implementations are used by {@code io.github.tokennudge.internal.NudgeLoop} from a
 * single loop thread; they do not need to be thread-safe with respect to each other, but
 * must tolerate being called repeatedly, in a tight loop, indefinitely.
 *
 * <h2>Failure contract for {@link #claim(WaitState)} and {@link #execute(WaitState, Action)}</h2>
 * <p>These two methods are the only ones where a wrong choice of exception can cause the
 * loop to double-act against the engine, so the contract is explicit:
 * <ul>
 *   <li>Throw {@link EngineAccessException} from {@code claim} or {@code execute} only when
 *       the request is known to have never reached the engine at all &mdash; for example, a
 *       connection could not even be established before anything was sent. The loop treats
 *       this as safe to retry on the next iteration, because nothing was submitted.</li>
 *   <li>Any other {@link RuntimeException} thrown from {@code claim} or {@code execute}
 *       (including a timeout, a reset connection, or any other error that could plausibly
 *       occur only after the request was already sent) must be treated by callers as an
 *       <em>ambiguous, unknown outcome</em>: the request may or may not have been applied by
 *       the engine. The loop never retries such a failure &mdash; retrying could double-act,
 *       for example decrementing an external task's retry count twice &mdash; and instead
 *       journals {@link io.github.tokennudge.model.Outcome#ACTION_FAILED} with an
 *       {@code "outcome unknown: ..."} error message and marks the wait state handled.</li>
 *   <li>{@link EngineActionException} remains the preferred, more informative way to report
 *       a request that was definitely rejected by the engine (for example, a 4xx response
 *       with a clear reason). Prefer it over a generic {@link RuntimeException} whenever the
 *       engine's own response makes the outcome unambiguous.</li>
 *   <li>{@link EngineWaitStateGoneException}, thrown only from {@code execute}, reports a
 *       different, still-unambiguous fact: the engine authoritatively confirms the wait
 *       state no longer exists, because another worker, a human, or the process itself
 *       already handled it first. This is a benign race, not a failure &mdash; the loop
 *       journals {@link io.github.tokennudge.model.Outcome#CLAIM_LOST}, exactly as it does
 *       for {@link ClaimResult#LOST} from {@code claim}, rather than
 *       {@link io.github.tokennudge.model.Outcome#ACTION_FAILED}.</li>
 * </ul>
 * <h3>The JDK {@code HttpClient} trap</h3>
 * <p>Adapters built on {@code java.net.http.HttpClient} (as {@code tokennudge-camunda7} is)
 * must map its exceptions carefully, because not every {@link java.io.IOException} it throws
 * means "not delivered":
 * <ul>
 *   <li>Only a <em>connect-phase</em> failure counts as "not delivered": a
 *       {@link java.net.ConnectException} (the connection could not be established at all),
 *       a {@link java.net.http.HttpConnectTimeoutException} (connecting itself timed out),
 *       or any other failure thrown before the request has been handed to the socket. Only
 *       these may become {@link EngineAccessException}.</li>
 *   <li>A {@link java.net.http.HttpTimeoutException} thrown by
 *       {@code HttpClient.send(...)} after the connection was already established (for
 *       example, the response never arrived within the request timeout), or any other
 *       {@link java.io.IOException} from {@code send(...)} once the connection is up, is
 *       <strong>ambiguous</strong>: the request may already have reached the engine and been
 *       applied. It must be treated per the "any other {@code RuntimeException}" rule above,
 *       never wrapped as {@link EngineAccessException}.</li>
 *   <li>Note that {@link java.net.http.HttpConnectTimeoutException} is itself a subclass of
 *       {@link java.net.http.HttpTimeoutException}: an adapter's {@code catch} logic must
 *       check for the more specific connect-timeout subclass first, or it will
 *       misclassify every connect timeout as ambiguous instead of "not delivered".</li>
 * </ul>
 * <p>For {@code claim} specifically: an ambiguous failure is journaled as
 * {@code ACTION_FAILED}, not {@link io.github.tokennudge.model.Outcome#CLAIM_LOST}, because
 * {@code CLAIM_LOST} means the engine authoritatively reported that another worker claimed
 * or completed the wait state first &mdash; a known, benign fact. An ambiguous claim failure
 * carries no such information: this worker might now hold the claim, another worker might,
 * or the request might never have arrived; only {@code ACTION_FAILED} with an
 * {@code "outcome unknown: ..."} message reflects that honestly, and (like every other
 * terminal outcome) it is never retried blindly. Note the resulting side effect: if the
 * claim actually succeeded on the engine despite the ambiguous failure, this worker holds
 * the claim but never acts on it, so the wait state appears stuck until the claim's lock
 * duration expires and another worker becomes able to claim it again.
 */
public interface EngineAdapter extends AutoCloseable {

    /**
     * Verifies that the engine is reachable, failing fast with a clear error if not.
     * Called once when a {@code TokenNudge} is started.
     *
     * @throws EngineAccessException if the engine cannot be reached
     */
    void checkConnectivity();

    /**
     * Discovers wait states of a given kind, limited to the requested names. Read-only:
     * must not lock, claim, or otherwise mutate anything in the engine.
     *
     * @param query the discovery request, never {@code null}
     * @return the discovered wait states; never {@code null}, may be empty
     * @throws EngineAccessException if the engine cannot be reached or the discovery
     *                                request fails
     */
    List<WaitState> discover(DiscoveryQuery query);

    /**
     * Attempts to claim (for example, lock) a wait state so that only this worker acts on
     * it.
     *
     * @param waitState the wait state to claim, never {@code null}
     * @return {@link ClaimResult#CLAIMED} if successful, {@link ClaimResult#LOST} if
     *         another worker claimed or completed it first (not an error)
     * @throws EngineAccessException if the request was definitely not delivered; see this
     *                                interface's class Javadoc "Failure contract" section
     *                                for what other exceptions mean and how they are
     *                                handled
     */
    ClaimResult claim(WaitState waitState);

    /**
     * Releases a previously claimed wait state without acting on it, for adapters that use
     * a fetch-and-lock style claim strategy where unmatched wait states must be explicitly
     * unlocked. The default implementation does nothing, which is correct for adapters that
     * only ever claim wait states they are about to act on.
     *
     * @param waitState the wait state to release, never {@code null}
     */
    default void release(WaitState waitState) {
        // no-op by default
    }

    /**
     * Returns a snapshot of the process variables visible at a wait state.
     *
     * @param waitState the wait state to fetch variables for, never {@code null}
     * @return the visible variables, keyed by name; never {@code null}, may be empty
     * @throws EngineAccessException if the engine cannot be reached
     */
    Map<String, Object> variables(WaitState waitState);

    /**
     * Executes an action against a claimed wait state (for example, completing an external
     * task).
     *
     * @param waitState the wait state to act on, never {@code null}
     * @param action    the action to perform, never {@code null}
     * @throws EngineActionException       if the engine was reached but rejected the action
     * @throws EngineWaitStateGoneException if the engine authoritatively reports that the
     *                                       wait state no longer exists (a benign race, not
     *                                       a failure); see this interface's class Javadoc
     *                                       "Failure contract" section
     * @throws EngineAccessException if the request was definitely not delivered; see this
     *                                interface's class Javadoc "Failure contract" section
     *                                for what other exceptions mean and how they are
     *                                handled
     */
    void execute(WaitState waitState, Action action);

    /**
     * Releases any resources held by this adapter (for example, an HTTP client). Called
     * once when a {@code TokenNudge} is stopped.
     */
    @Override
    void close();
}
