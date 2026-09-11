package io.github.tokennudge.model;

import io.github.tokennudge.SimulationId;
import io.github.tokennudge.internal.DeepFreeze;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, append-only record of one processed {@link WaitState}: which simulation
 * (if any) matched, which action (if any) was attempted, its outcome, the process
 * variables visible at the wait state, and the error message (if any).
 *
 * @param sequence             a monotonically increasing, journal-wide sequence number;
 *                             must not be negative
 * @param timestamp            when this entry was recorded, never {@code null}
 * @param waitState            the wait state this entry is about, never {@code null}
 * @param simulationId         the id of the simulation that matched, or empty if none did
 *                             (see {@link Outcome#UNMATCHED}); never {@code null}
 * @param action               the action that was attempted, or empty if none was (see
 *                             {@link Outcome#UNMATCHED}); never {@code null}
 * @param outcome              the outcome of processing this wait state, never
 *                             {@code null}
 * @param variablesAtWaitState a snapshot of the process variables visible when this wait
 *                             state was handled; never {@code null}, may be empty. Copied
 *                             recursively (see {@link DeepFreeze}) so that neither
 *                             constructing this entry nor reading this accessor can observe
 *                             mutation from the other side.
 * @param error                a human-readable error message, present when
 *                             {@code outcome} is {@link Outcome#ACTION_FAILED}; never
 *                             {@code null}
 */
public record JournalEntry(
        long sequence,
        Instant timestamp,
        WaitState waitState,
        Optional<SimulationId> simulationId,
        Optional<Action> action,
        Outcome outcome,
        Map<String, Object> variablesAtWaitState,
        Optional<String> error) {

    /**
     * Validates required fields and makes a defensive, recursively unmodifiable copy of
     * {@code variablesAtWaitState}.
     *
     * @throws NullPointerException     if {@code sequence}, {@code timestamp},
     *                                  {@code waitState}, {@code simulationId},
     *                                  {@code action}, {@code outcome},
     *                                  {@code variablesAtWaitState}, or {@code error} is
     *                                  {@code null}. An {@code Optional} parameter must
     *                                  itself not be {@code null}, but may be
     *                                  {@link Optional#empty()}.
     * @throws IllegalArgumentException if {@code sequence} is negative
     */
    public JournalEntry {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(waitState, "waitState must not be null");
        Objects.requireNonNull(simulationId, "simulationId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(variablesAtWaitState, "variablesAtWaitState must not be null");
        Objects.requireNonNull(error, "error must not be null");
        variablesAtWaitState = DeepFreeze.freeze(variablesAtWaitState);
    }

    /**
     * Returns a defensive, recursively unmodifiable copy of the process variables visible
     * when this wait state was handled.
     *
     * <p>Every call returns a freshly cloned/rebuilt copy (see {@link DeepFreeze}), so
     * mutating anything reachable from a previously returned map (where the runtime type
     * otherwise permits it) never affects this entry or any other call to this accessor.
     *
     * @return the variable snapshot, never {@code null}
     */
    @Override
    public Map<String, Object> variablesAtWaitState() {
        return DeepFreeze.freeze(variablesAtWaitState);
    }
}
