package io.github.tokennudge.internal;

import io.github.tokennudge.Simulation;
import io.github.tokennudge.SimulationId;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, copy-on-write registry of {@link Simulation}s.
 *
 * <p>{@link #snapshot()} returns the registered simulations sorted by priority ascending
 * (lower priority value wins, {@code 1} highest), then by registration order descending
 * (the most recently registered simulation among equal priorities wins) &mdash; the same
 * precedence rule WireMock uses for stub mappings.
 *
 * <p>Not public API; see the package documentation.
 */
public final class SimulationRegistry {

    private record Entry(Simulation simulation, long registrationSequence) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong nextRegistrationSequence = new AtomicLong();

    /**
     * Registers a simulation.
     *
     * @param simulation the simulation to register, never {@code null}
     * @throws NullPointerException if {@code simulation} is {@code null}
     */
    public void add(Simulation simulation) {
        Objects.requireNonNull(simulation, "simulation must not be null");
        entries.add(new Entry(simulation, nextRegistrationSequence.getAndIncrement()));
    }

    /**
     * Removes the simulation with the given id, if registered.
     *
     * @param id the id of the simulation to remove, never {@code null}
     * @return {@code true} if a simulation was removed
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public boolean remove(SimulationId id) {
        Objects.requireNonNull(id, "id must not be null");
        return entries.removeIf(entry -> entry.simulation().id().equals(id));
    }

    /**
     * Returns a snapshot of the currently registered simulations, ordered by precedence:
     * lowest priority first, then most recently registered first among equal priorities.
     *
     * @return an immutable, ordered snapshot; never {@code null}
     */
    public List<Simulation> snapshot() {
        return entries.stream()
                .sorted(Comparator.<Entry>comparingInt(entry -> entry.simulation().priority())
                        .thenComparing(Comparator.comparingLong(Entry::registrationSequence).reversed()))
                .map(Entry::simulation)
                .toList();
    }

    /**
     * Removes all registered simulations.
     */
    public void clear() {
        entries.clear();
    }
}
