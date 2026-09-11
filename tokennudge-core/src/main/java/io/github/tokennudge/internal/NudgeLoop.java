package io.github.tokennudge.internal;

import io.github.tokennudge.Simulation;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.model.WaitStateSelector;
import io.github.tokennudge.spi.ClaimResult;
import io.github.tokennudge.spi.DiscoveryQuery;
import io.github.tokennudge.spi.EngineAccessException;
import io.github.tokennudge.spi.EngineActionException;
import io.github.tokennudge.spi.EngineAdapter;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the discover / match / claim / act cycle on a single daemon thread named
 * {@code tokennudge-loop}.
 *
 * <p>Each iteration: takes a precedence-ordered snapshot of the registered simulations,
 * groups them by {@link WaitStateKind} and distinct name, discovers wait states per group,
 * and for every discovered wait state not already in {@link HandledWaitStates}, finds the
 * first fully matching simulation (static fields first; variables are only fetched when a
 * statically-matching candidate needs them, or {@code captureVariables} is enabled),
 * journals {@link Outcome#UNMATCHED} if none matched, or claims and executes the matched
 * simulation's action, journaling {@link Outcome#HANDLED}, {@link Outcome#ACTION_FAILED},
 * or {@link Outcome#CLAIM_LOST} and marking the wait state handled either way (no automatic
 * retry).
 *
 * <p>If an iteration did any work (matched at least one wait state), the next iteration
 * starts immediately; otherwise the loop waits up to {@code pollInterval} on an internal
 * condition that {@link #wake()} (called by {@code simulate()}/{@code verify()}) can signal
 * early. A discovery failure is logged at {@code WARNING} and only skips that group; any
 * other unexpected {@link RuntimeException} during an iteration is logged and never kills
 * the loop.
 *
 * <p><b>Adapter failure contract for {@code claim}/{@code execute}:</b> an
 * {@link EngineAccessException} from either call means the request was definitely not
 * delivered, so it is logged and the wait state is left to be retried on the next
 * iteration. Any other {@link RuntimeException} from either call (other than
 * {@link EngineActionException}, which is a definite, informative rejection) is an
 * ambiguous, unknown outcome: it is never retried. The wait state is journaled as
 * {@link Outcome#ACTION_FAILED} with an {@code "outcome unknown: ..."} error and marked
 * handled &mdash; for {@code claim}, in preference to {@link Outcome#CLAIM_LOST}, since
 * {@code CLAIM_LOST} means the engine authoritatively reported the wait state was already
 * taken, which an ambiguous failure does not tell us. See {@link EngineAdapter}'s class
 * Javadoc for the full contract.
 *
 * <p>Not public API; see the package documentation.
 */
public final class NudgeLoop {

    private static final System.Logger LOGGER = System.getLogger(NudgeLoop.class.getName());

    private final EngineAdapter adapter;
    private final SimulationRegistry registry;
    private final InMemoryJournal journal;
    private final HandledWaitStates handledWaitStates = new HandledWaitStates();
    private final ReentrantLock iterationLock;
    private final Duration pollInterval;
    private final boolean captureVariables;
    private final int maxResultsPerPoll;

    private final Object idleLock = new Object();
    private long iterationCount = 0;
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private volatile Thread thread;

    /**
     * Creates a new loop. The loop is not started until {@link #start()} is called.
     *
     * @param adapter           the engine adapter to discover/claim/act through, never
     *                          {@code null}
     * @param registry          the simulation registry to read snapshots from, never
     *                          {@code null}
     * @param journal           the journal to record outcomes to, never {@code null}
     * @param iterationLock     the lock held for the duration of one iteration's work,
     *                          shared with the facade's {@code reset()}/{@code stop()} so
     *                          that a stale iteration can never write into a journal that
     *                          was just cleared; never {@code null}
     * @param pollInterval      how long to wait for new work before polling again when an
     *                          iteration did nothing, never {@code null} or negative
     * @param captureVariables  whether to always fetch variables for a statically-matching
     *                          wait state, even if the matching simulation's selector does
     *                          not itself require them
     * @param maxResultsPerPoll the maximum number of wait states to request per discovery
     *                          call; must be positive
     */
    public NudgeLoop(
            EngineAdapter adapter,
            SimulationRegistry registry,
            InMemoryJournal journal,
            ReentrantLock iterationLock,
            Duration pollInterval,
            boolean captureVariables,
            int maxResultsPerPoll) {
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.iterationLock = Objects.requireNonNull(iterationLock, "iterationLock must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must not be negative: " + pollInterval);
        }
        this.captureVariables = captureVariables;
        if (maxResultsPerPoll <= 0) {
            throw new IllegalArgumentException("maxResultsPerPoll must be positive: " + maxResultsPerPoll);
        }
        this.maxResultsPerPoll = maxResultsPerPoll;
    }

    /**
     * Starts the loop thread. Idempotent: calling this again while already running does
     * nothing.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        stopRequested = false;
        running = true;
        thread = new Thread(this::runLoop, "tokennudge-loop");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Signals the loop thread to stop after its current iteration, wakes it if idle, and
     * joins it for up to the given timeout. Idempotent: calling this again (or before
     * {@link #start()}) does nothing.
     *
     * <p>If called from the loop thread itself (for example, from an {@link EngineAdapter}
     * callback invoked during an iteration that goes on to call this method), the join is
     * skipped: a thread can never usefully join itself, and attempting to would simply
     * block for the full {@code joinTimeout} instead of returning promptly.
     *
     * @param joinTimeout how long to wait for the loop thread to terminate, never
     *                    {@code null} or negative
     */
    public synchronized void stop(Duration joinTimeout) {
        Objects.requireNonNull(joinTimeout, "joinTimeout must not be null");
        if (!running) {
            return;
        }
        running = false;
        iterationLock.lock();
        try {
            stopRequested = true;
        } finally {
            iterationLock.unlock();
        }
        synchronized (idleLock) {
            idleLock.notifyAll();
        }
        if (thread != null) {
            if (Thread.currentThread() != thread) {
                try {
                    thread.join(joinTimeout.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            thread = null;
        }
    }

    /**
     * Returns whether the loop is currently running (between {@link #start()} and the
     * beginning of {@link #stop(Duration)}).
     *
     * @return {@code true} if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the number of iterations completed so far.
     *
     * @return the completed iteration count
     */
    public long iterationCount() {
        synchronized (idleLock) {
            return iterationCount;
        }
    }

    /**
     * Returns whether the calling thread is this loop's own background thread (named
     * {@code tokennudge-loop}).
     *
     * @return {@code true} if called from the loop thread itself
     */
    public boolean isCurrentThreadTheLoopThread() {
        return Thread.currentThread() == thread;
    }

    /**
     * Returns an iteration-count baseline suitable for awaiting a genuinely <em>fresh</em>
     * iteration &mdash; one that starts, not merely finishes, after this method returns.
     *
     * <p>{@link #iterationCount()} alone is not enough for this: {@code iterationCount} is
     * only incremented once an iteration's work is done, so a caller could read it while an
     * iteration that had already started (and taken its discovery snapshot) is still in
     * flight, then wrongly treat that stale iteration's completion as "one full iteration
     * that started after the call" (see {@code VerificationEvaluator}'s non-monotonic
     * awaiting). This method closes that gap by acquiring {@code iterationLock} (up to
     * {@code timeout}) before reading the count: since the count is now incremented while
     * {@code iterationLock} is still held (see {@link #runLoop()}), acquiring the lock
     * first guarantees that any iteration already in flight has both finished its work
     * <em>and</em> been counted by the time this method reads it. Any iteration that goes
     * on to increment the count afterwards can only have started (acquired
     * {@code iterationLock}) strictly after this method released it.
     *
     * <p>If {@code iterationLock} cannot be acquired within {@code timeout}, or the wait is
     * interrupted, no baseline can be honestly reported: falling back to the current,
     * possibly-stale {@link #iterationCount()} would let a caller treat an iteration that
     * was already in flight (and is still holding {@code iterationLock} for longer than the
     * caller's entire timeout budget) as having started after this call, which is exactly
     * the bug this method exists to prevent. Callers must therefore treat an empty result as
     * a timeout in its own right, not silently fall back to a stale count.
     *
     * @param timeout how long to wait to acquire {@code iterationLock}, never {@code null}
     *                or negative
     * @return the baseline iteration count, or {@link OptionalLong#empty()} if
     *         {@code iterationLock} could not be acquired within {@code timeout} (including
     *         if the wait was interrupted)
     */
    public OptionalLong freshIterationBaseline(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        boolean acquired;
        try {
            acquired = iterationLock.tryLock(Math.max(timeout.toNanos(), 0), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalLong.empty();
        }
        if (!acquired) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(iterationCount());
        } finally {
            iterationLock.unlock();
        }
    }

    /**
     * Wakes the loop early if it is currently idle-waiting for {@code pollInterval}.
     * Harmless (a no-op) if the loop is busy, not running, or not currently waiting.
     */
    public void wake() {
        synchronized (idleLock) {
            idleLock.notifyAll();
        }
    }

    /**
     * Forgets every wait-state id previously marked as handled, so wait states discovered
     * again will be re-matched against the (presumably now different) registered
     * simulations. Callers are responsible for coordinating this with {@code iterationLock}
     * if needed; this method itself only guarantees {@link HandledWaitStates} is left in a
     * consistent state.
     */
    public void resetHandledWaitStates() {
        handledWaitStates.clear();
    }

    /**
     * Blocks the calling thread until either the iteration count is greater than
     * {@code fromIteration}, or {@link System#nanoTime()} reaches {@code deadlineNanos},
     * whichever happens first.
     *
     * @param fromIteration the iteration count to wait for a change from
     * @param deadlineNanos the deadline, compared against {@link System#nanoTime()}
     */
    public void awaitIterationAfter(long fromIteration, long deadlineNanos) {
        synchronized (idleLock) {
            while (iterationCount <= fromIteration) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return;
                }
                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);
                try {
                    idleLock.wait(millis, nanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void runLoop() {
        while (!stopRequested) {
            boolean didWork;
            iterationLock.lock();
            try {
                didWork = safeRunOneIteration();
                // Incrementing while iterationLock is still held (rather than after
                // releasing it) is what lets freshIterationBaseline() guarantee that
                // acquiring iterationLock always observes an up-to-date count: see its
                // Javadoc and VerificationEvaluator.awaitNonMonotonic.
                synchronized (idleLock) {
                    iterationCount++;
                    idleLock.notifyAll();
                }
            } finally {
                iterationLock.unlock();
            }
            if (!didWork && !stopRequested) {
                synchronized (idleLock) {
                    long millis = pollInterval.toMillis();
                    int nanosRemainder = (int) (pollInterval.toNanos() % 1_000_000L);
                    try {
                        idleLock.wait(millis, nanosRemainder);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private boolean safeRunOneIteration() {
        try {
            return runOneIteration();
        } catch (RuntimeException e) {
            LOGGER.log(Level.ERROR, "Unexpected error running a TokenNudge loop iteration; continuing", e);
            return false;
        }
    }

    private boolean runOneIteration() {
        List<Simulation> rules = registry.snapshot();
        if (rules.isEmpty()) {
            return false;
        }

        Map<WaitStateKind, Set<String>> namesByKind = groupNames(rules);
        List<WaitState> discovered = discoverAll(namesByKind);

        boolean didWork = false;
        for (WaitState waitState : discovered) {
            if (handledWaitStates.contains(waitState.id())) {
                continue;
            }
            if (processWaitState(waitState, rules)) {
                didWork = true;
            }
        }
        return didWork;
    }

    private static Map<WaitStateKind, Set<String>> groupNames(List<Simulation> rules) {
        Map<WaitStateKind, Set<String>> namesByKind = new LinkedHashMap<>();
        for (Simulation rule : rules) {
            namesByKind
                    .computeIfAbsent(rule.selector().kind(), kind -> new LinkedHashSet<>())
                    .add(rule.selector().name());
        }
        return namesByKind;
    }

    private List<WaitState> discoverAll(Map<WaitStateKind, Set<String>> namesByKind) {
        List<WaitState> discovered = new ArrayList<>();
        for (Map.Entry<WaitStateKind, Set<String>> group : namesByKind.entrySet()) {
            try {
                DiscoveryQuery query = new DiscoveryQuery(group.getKey(), group.getValue(), maxResultsPerPoll);
                discovered.addAll(adapter.discover(query));
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        "Discovery failed for kind " + group.getKey() + ", names " + group.getValue()
                                + "; skipping this group for this iteration",
                        e);
            }
        }
        return discovered;
    }

    /**
     * Finds the first fully matching simulation for a wait state, per {@link Simulation}
     * precedence order, fetching variables lazily and at most once.
     */
    private MatchResult findMatch(WaitState waitState, List<Simulation> rules) {
        Map<String, Object> variables = Map.of();
        boolean fetched = false;
        for (Simulation candidate : rules) {
            WaitStateSelector selector = candidate.selector();
            if (!selector.matchesStatic(waitState)) {
                continue;
            }
            if (!fetched && (selector.requiresVariables() || captureVariables)) {
                variables = fetchVariables(waitState);
                fetched = true;
            }
            if (selector.matchesVariables(variables)) {
                return new MatchResult(candidate, variables);
            }
        }
        return new MatchResult(null, variables);
    }

    private Map<String, Object> fetchVariables(WaitState waitState) {
        try {
            return adapter.variables(waitState);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to fetch variables for wait state " + waitState.id(), e);
            return Map.of();
        }
    }

    /**
     * Processes one discovered wait state: matches, then claims and executes if matched.
     *
     * @return {@code true} if this wait state was matched (regardless of the eventual
     *         claim/action outcome), signalling that this iteration did work
     */
    private boolean processWaitState(WaitState waitState, List<Simulation> rules) {
        MatchResult match = findMatch(waitState, rules);
        Simulation simulation = match.simulation();
        if (simulation == null) {
            journal.append(
                    waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, match.variables(),
                    Optional.empty());
            return false;
        }

        ClaimResult claimResult;
        try {
            claimResult = adapter.claim(waitState);
        } catch (EngineAccessException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Failed to claim wait state " + waitState.id() + " (request not delivered); will retry "
                            + "next iteration",
                    e);
            return false;
        } catch (RuntimeException e) {
            // Ambiguous outcome: the claim request may or may not have reached the engine
            // and been applied. Per the EngineAdapter contract, this is never retried
            // blindly; it is journaled as ACTION_FAILED (not CLAIM_LOST, which would
            // wrongly imply the engine told us someone else has it) and marked handled.
            handledWaitStates.add(waitState.id());
            journal.append(
                    waitState,
                    Optional.of(simulation.id()),
                    Optional.of(simulation.action()),
                    Outcome.ACTION_FAILED,
                    match.variables(),
                    Optional.of(ambiguousOutcomeMessage(e)));
            LOGGER.log(
                    Level.WARNING,
                    "Ambiguous failure claiming wait state " + waitState.id() + "; recording ACTION_FAILED, "
                            + "not retrying",
                    e);
            return true;
        }

        if (claimResult == ClaimResult.LOST) {
            handledWaitStates.add(waitState.id());
            journal.append(
                    waitState,
                    Optional.of(simulation.id()),
                    Optional.of(simulation.action()),
                    Outcome.CLAIM_LOST,
                    match.variables(),
                    Optional.empty());
            return true;
        }

        return execute(waitState, simulation, match.variables());
    }

    private boolean execute(WaitState waitState, Simulation simulation, Map<String, Object> variables) {
        try {
            adapter.execute(waitState, simulation.action());
        } catch (EngineActionException e) {
            // Definite, informative rejection by the engine: safe to record as-is.
            handledWaitStates.add(waitState.id());
            journal.append(
                    waitState,
                    Optional.of(simulation.id()),
                    Optional.of(simulation.action()),
                    Outcome.ACTION_FAILED,
                    variables,
                    Optional.ofNullable(e.getMessage()));
            return true;
        } catch (EngineAccessException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Failed to execute action for wait state " + waitState.id() + " (request not delivered); "
                            + "will retry next iteration",
                    e);
            return false;
        } catch (RuntimeException e) {
            // Ambiguous outcome: the action may or may not have been applied by the engine
            // (for example, a timeout after the request was already sent). Per the
            // EngineAdapter contract, this is never retried blindly, to avoid double-acting
            // (e.g. decrementing an external task's retry count twice); it is journaled as
            // ACTION_FAILED and marked handled.
            handledWaitStates.add(waitState.id());
            journal.append(
                    waitState,
                    Optional.of(simulation.id()),
                    Optional.of(simulation.action()),
                    Outcome.ACTION_FAILED,
                    variables,
                    Optional.of(ambiguousOutcomeMessage(e)));
            LOGGER.log(
                    Level.WARNING,
                    "Ambiguous failure executing action for wait state " + waitState.id() + "; recording "
                            + "ACTION_FAILED, not retrying",
                    e);
            return true;
        }
        handledWaitStates.add(waitState.id());
        journal.append(
                waitState,
                Optional.of(simulation.id()),
                Optional.of(simulation.action()),
                Outcome.HANDLED,
                variables,
                Optional.empty());
        return true;
    }

    /**
     * Builds the {@code "outcome unknown: ..."} error message journaled for an ambiguous
     * {@code claim}/{@code execute} failure (see the class Javadoc's "Adapter failure
     * contract" section), falling back to the exception's simple class name if it carries
     * no message.
     */
    private static String ambiguousOutcomeMessage(RuntimeException e) {
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "outcome unknown: " + detail;
    }

    private record MatchResult(Simulation simulation, Map<String, Object> variables) {
    }
}
