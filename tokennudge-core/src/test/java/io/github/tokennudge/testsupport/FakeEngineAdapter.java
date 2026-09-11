package io.github.tokennudge.testsupport;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.spi.ClaimResult;
import io.github.tokennudge.spi.DiscoveryQuery;
import io.github.tokennudge.spi.EngineAccessException;
import io.github.tokennudge.spi.EngineActionException;
import io.github.tokennudge.spi.EngineAdapter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * In-memory, programmable {@link EngineAdapter} for tests: no real transport, just plain
 * Java collections. Lets a test declare which wait states are "discoverable", what
 * variables they carry, and simulate a lost claim, an action failure, or a discovery
 * failure, then inspect every action that was actually executed.
 *
 * <p>Ships in {@code tokennudge-core}'s test-jar precisely so that other modules'
 * tests (for example {@code tokennudge-junit5}, in a later iteration) can reuse it against
 * the real {@code NudgeLoop}/{@code TokenNudge}, without depending on {@code internal}
 * classes across a module boundary.
 */
public final class FakeEngineAdapter implements EngineAdapter {

    /**
     * One action the loop executed against a wait state, as recorded by
     * {@link #execute(WaitState, Action)}.
     *
     * @param waitState the wait state the action was executed against
     * @param action    the action that was executed
     */
    public record ExecutedAction(WaitState waitState, Action action) {
    }

    private final List<WaitState> waitStates = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Object>> variablesById = new ConcurrentHashMap<>();
    private final Set<String> claimLostIds = ConcurrentHashMap.newKeySet();
    private final Set<String> actionFailureIds = ConcurrentHashMap.newKeySet();
    private final Set<WaitStateKind> discoveryFailureKinds = ConcurrentHashMap.newKeySet();
    private final List<ExecutedAction> executedActions = new CopyOnWriteArrayList<>();
    private volatile boolean connectivityFails = false;
    private volatile boolean closed = false;
    private final Set<String> notDeliveredClaimIds = ConcurrentHashMap.newKeySet();
    private final Set<String> ambiguousClaimFailureIds = ConcurrentHashMap.newKeySet();
    private final Set<String> notDeliveredExecuteIds = ConcurrentHashMap.newKeySet();
    private final Set<String> ambiguousExecuteFailureIds = ConcurrentHashMap.newKeySet();
    private volatile CountDownLatch discoveryEnteredLatch;
    private volatile CountDownLatch discoveryReleaseLatch;

    /**
     * Makes a wait state discoverable, with no variables.
     *
     * @param waitState the wait state to add, never {@code null}
     */
    public void addWaitState(WaitState waitState) {
        addWaitState(waitState, Map.of());
    }

    /**
     * Makes a wait state discoverable, with the given variables visible via
     * {@link #variables(WaitState)}.
     *
     * @param waitState the wait state to add, never {@code null}
     * @param variables the variables visible at this wait state, never {@code null}
     */
    public void addWaitState(WaitState waitState, Map<String, Object> variables) {
        Objects.requireNonNull(waitState, "waitState must not be null");
        Objects.requireNonNull(variables, "variables must not be null");
        waitStates.add(waitState);
        variablesById.put(waitState.id(), Map.copyOf(variables));
    }

    /**
     * Removes a wait state from discovery (simulating the token having moved past it).
     *
     * @param waitStateId the id of the wait state to remove, never {@code null}
     */
    public void removeWaitState(String waitStateId) {
        Objects.requireNonNull(waitStateId, "waitStateId must not be null");
        waitStates.removeIf(waitState -> waitState.id().equals(waitStateId));
    }

    /**
     * Makes {@link #claim(WaitState)} return {@link ClaimResult#LOST} for the given wait
     * state id.
     *
     * @param waitStateId the wait-state id to lose claims for, never {@code null}
     */
    public void simulateClaimLost(String waitStateId) {
        claimLostIds.add(Objects.requireNonNull(waitStateId, "waitStateId must not be null"));
    }

    /**
     * Makes {@link #execute(WaitState, Action)} throw {@link EngineActionException} for the
     * given wait state id.
     *
     * @param waitStateId the wait-state id to fail actions for, never {@code null}
     */
    public void simulateActionFailure(String waitStateId) {
        actionFailureIds.add(Objects.requireNonNull(waitStateId, "waitStateId must not be null"));
    }

    /**
     * Makes {@link #discover(DiscoveryQuery)} throw {@link EngineAccessException} for the
     * given kind.
     *
     * @param kind the kind to fail discovery for, never {@code null}
     */
    public void simulateDiscoveryFailure(WaitStateKind kind) {
        discoveryFailureKinds.add(Objects.requireNonNull(kind, "kind must not be null"));
    }

    /**
     * Makes {@link #checkConnectivity()} throw {@link EngineAccessException}.
     *
     * @param fails whether connectivity checks should fail
     */
    public void simulateConnectivityFailure(boolean fails) {
        this.connectivityFails = fails;
    }

    /**
     * Makes exactly the next {@link #claim(WaitState)} call for the given wait state id
     * throw {@link EngineAccessException}, simulating a request that was definitely not
     * delivered to the engine (see {@link EngineAdapter}'s "Failure contract" section). Any
     * subsequent claim for the same id succeeds normally, letting a test exercise "retried
     * and later succeeds".
     *
     * @param waitStateId the wait-state id to fail the next claim for, never {@code null}
     */
    public void simulateNotDeliveredClaimFailureOnce(String waitStateId) {
        notDeliveredClaimIds.add(Objects.requireNonNull(waitStateId, "waitStateId must not be null"));
    }

    /**
     * Makes every {@link #claim(WaitState)} call for the given wait state id throw a
     * generic {@link RuntimeException} (neither {@link EngineAccessException} nor
     * {@link EngineActionException}), simulating an ambiguous, unknown-outcome failure per
     * {@link EngineAdapter}'s "Failure contract" section.
     *
     * @param waitStateId the wait-state id to fail claims for, never {@code null}
     */
    public void simulateAmbiguousClaimFailure(String waitStateId) {
        ambiguousClaimFailureIds.add(Objects.requireNonNull(waitStateId, "waitStateId must not be null"));
    }

    /**
     * Makes exactly the next {@link #execute(WaitState, Action)} call for the given wait
     * state id throw {@link EngineAccessException}, simulating a request that was
     * definitely not delivered to the engine. Any subsequent execution for the same id
     * succeeds normally, letting a test exercise "retried and later succeeds".
     *
     * @param waitStateId the wait-state id to fail the next execution for, never
     *                    {@code null}
     */
    public void simulateNotDeliveredExecuteFailureOnce(String waitStateId) {
        notDeliveredExecuteIds.add(Objects.requireNonNull(waitStateId, "waitStateId must not be null"));
    }

    /**
     * Makes every {@link #execute(WaitState, Action)} call for the given wait state id
     * throw a generic {@link RuntimeException} (neither {@link EngineAccessException} nor
     * {@link EngineActionException}), simulating an ambiguous, unknown-outcome failure.
     *
     * @param waitStateId the wait-state id to fail executions for, never {@code null}
     */
    public void simulateAmbiguousExecuteFailure(String waitStateId) {
        ambiguousExecuteFailureIds.add(Objects.requireNonNull(waitStateId, "waitStateId must not be null"));
    }

    /**
     * Makes the next call to {@link #discover(DiscoveryQuery)} count down {@code entered}
     * as soon as it is invoked, then block until {@code release} counts down to zero,
     * before computing its result. Since both latches stay open once counted down, only
     * the first {@code discover(...)} call after this is configured actually blocks; every
     * later call returns immediately. Lets a test deterministically synchronize with "the
     * loop is currently inside discover()" without a fixed sleep.
     *
     * @param entered counted down as soon as {@link #discover(DiscoveryQuery)} is entered,
     *                never {@code null}
     * @param release awaited before {@link #discover(DiscoveryQuery)} computes its result,
     *                never {@code null}
     */
    public void blockNextDiscovery(CountDownLatch entered, CountDownLatch release) {
        this.discoveryEnteredLatch = Objects.requireNonNull(entered, "entered must not be null");
        this.discoveryReleaseLatch = Objects.requireNonNull(release, "release must not be null");
    }

    /**
     * Returns every action executed so far, in execution order.
     *
     * @return an immutable, ordered snapshot; never {@code null}
     */
    public List<ExecutedAction> executedActions() {
        return List.copyOf(executedActions);
    }

    /**
     * Returns whether {@link #close()} has been called.
     *
     * @return {@code true} if closed
     */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void checkConnectivity() {
        if (connectivityFails) {
            throw new EngineAccessException("fake connectivity failure");
        }
    }

    @Override
    public List<WaitState> discover(DiscoveryQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        CountDownLatch entered = discoveryEnteredLatch;
        CountDownLatch release = discoveryReleaseLatch;
        if (entered != null) {
            entered.countDown();
        }
        if (release != null) {
            awaitUninterruptibly(release);
        }
        if (discoveryFailureKinds.contains(query.kind())) {
            throw new EngineAccessException("fake discovery failure for kind " + query.kind());
        }
        return waitStates.stream()
                .filter(waitState -> waitState.kind() == query.kind() && query.names().contains(waitState.name()))
                .toList();
    }

    @Override
    public ClaimResult claim(WaitState waitState) {
        Objects.requireNonNull(waitState, "waitState must not be null");
        if (notDeliveredClaimIds.remove(waitState.id())) {
            throw new EngineAccessException("fake not-delivered claim failure for wait state " + waitState.id());
        }
        if (ambiguousClaimFailureIds.contains(waitState.id())) {
            throw new IllegalStateException("fake ambiguous claim failure for wait state " + waitState.id());
        }
        return claimLostIds.contains(waitState.id()) ? ClaimResult.LOST : ClaimResult.CLAIMED;
    }

    @Override
    public Map<String, Object> variables(WaitState waitState) {
        Objects.requireNonNull(waitState, "waitState must not be null");
        return variablesById.getOrDefault(waitState.id(), Map.of());
    }

    @Override
    public void execute(WaitState waitState, Action action) {
        Objects.requireNonNull(waitState, "waitState must not be null");
        Objects.requireNonNull(action, "action must not be null");
        if (notDeliveredExecuteIds.remove(waitState.id())) {
            throw new EngineAccessException("fake not-delivered execute failure for wait state " + waitState.id());
        }
        if (ambiguousExecuteFailureIds.contains(waitState.id())) {
            throw new IllegalStateException("fake ambiguous execute failure for wait state " + waitState.id());
        }
        if (actionFailureIds.contains(waitState.id())) {
            throw new EngineActionException("fake action failure for wait state " + waitState.id());
        }
        executedActions.add(new ExecutedAction(waitState, action));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
