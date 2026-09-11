package io.github.tokennudge.internal;

import io.github.tokennudge.SimulationId;
import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.model.WaitState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, append-only journal of {@link JournalEntry} records.
 *
 * <p>Sequence numbers are monotonically increasing across the lifetime of a journal
 * instance (or since the last {@link #reset()}). An {@link Outcome#UNMATCHED} entry is
 * recorded at most once per distinct {@link WaitState#id()}; subsequent attempts to record
 * an unmatched entry for the same wait state id are silently ignored (see
 * {@link #append(WaitState, Optional, Optional, Outcome, Map, Optional)}).
 *
 * <p>Not public API; see the package documentation.
 */
public final class InMemoryJournal {

    private final List<JournalEntry> entries = new CopyOnWriteArrayList<>();
    private final Set<String> unmatchedWaitStateIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextSequence = new AtomicLong();

    /**
     * Appends a new entry, unless it would be a duplicate {@link Outcome#UNMATCHED} record
     * for a wait state id already journaled as unmatched, in which case nothing is
     * recorded.
     *
     * @param waitState             the wait state this entry is about, never {@code null}
     * @param simulationId          the id of the simulation that matched, or empty, never
     *                              {@code null}
     * @param action                the action that was attempted, or empty, never
     *                              {@code null}
     * @param outcome               the outcome, never {@code null}
     * @param variablesAtWaitState  the process variables visible at the wait state, never
     *                              {@code null}
     * @param error                 a human-readable error message, or empty, never
     *                              {@code null}
     * @return the newly appended entry, or empty if it was deduplicated
     * @throws NullPointerException if any parameter is {@code null}
     */
    public Optional<JournalEntry> append(
            WaitState waitState,
            Optional<SimulationId> simulationId,
            Optional<Action> action,
            Outcome outcome,
            Map<String, Object> variablesAtWaitState,
            Optional<String> error) {
        Objects.requireNonNull(waitState, "waitState must not be null");
        Objects.requireNonNull(simulationId, "simulationId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(variablesAtWaitState, "variablesAtWaitState must not be null");
        Objects.requireNonNull(error, "error must not be null");

        if (outcome == Outcome.UNMATCHED && !unmatchedWaitStateIds.add(waitState.id())) {
            return Optional.empty();
        }

        JournalEntry entry = new JournalEntry(
                nextSequence.getAndIncrement(),
                Instant.now(),
                waitState,
                simulationId,
                action,
                outcome,
                variablesAtWaitState,
                error);
        entries.add(entry);
        return Optional.of(entry);
    }

    /**
     * Returns a copy of every entry recorded so far, in recording order.
     *
     * @return an immutable, ordered snapshot; never {@code null}
     */
    public List<JournalEntry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Clears all recorded entries and the unmatched-wait-state deduplication memory.
     *
     * <p>Callers coordinating this with a running loop are responsible for their own
     * synchronization (for example an iteration lock); this method itself is thread-safe
     * only with respect to leaving the journal in a consistent (not corrupted) state.
     */
    public void reset() {
        entries.clear();
        unmatchedWaitStateIds.clear();
    }
}
