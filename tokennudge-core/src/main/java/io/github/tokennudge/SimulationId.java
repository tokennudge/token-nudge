package io.github.tokennudge;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque identifier of a registered {@code Simulation}.
 *
 * <p>Introduced ahead of the simulation registry (a later iteration) because
 * {@link io.github.tokennudge.model.JournalEntry} already references it: a journal entry
 * records which simulation, if any, handled a given wait state.
 *
 * @param value the wrapped identifier, never {@code null}
 */
public record SimulationId(UUID value) {

    /**
     * Validates the wrapped identifier.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public SimulationId {
        Objects.requireNonNull(value, "value must not be null");
    }

    /**
     * Creates a new, randomly generated {@link SimulationId}.
     *
     * @return a fresh identifier
     */
    public static SimulationId newId() {
        return new SimulationId(UUID.randomUUID());
    }
}
