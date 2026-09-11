package io.github.tokennudge;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.WaitStateSelector;

import java.util.Objects;

/**
 * A registered rule ("simulator", in WireMock terms): when the engine reaches a wait state
 * matching {@link #selector()}, perform {@link #action()}.
 *
 * <p>Instances are immutable; {@link #atPriority(int)} returns a copy with a different
 * priority, preserving this simulation's identity ({@link #id()}).
 *
 * <p>Rule precedence follows WireMock semantics: the lowest {@link #priority()} wins; among
 * simulations with equal priority, the most recently registered one wins (see
 * {@code io.github.tokennudge.internal.SimulationRegistry}).
 */
public final class Simulation {

    /**
     * The default priority assigned to a simulation that has not called
     * {@link #atPriority(int)}. Lower values take precedence; {@code 1} is the highest
     * possible priority.
     */
    public static final int DEFAULT_PRIORITY = 5;

    private final SimulationId id;
    private final WaitStateSelector selector;
    private final Action action;
    private final int priority;

    /**
     * Creates a new simulation with a freshly generated {@link SimulationId} and the
     * {@linkplain #DEFAULT_PRIORITY default priority}.
     *
     * @param selector the selector identifying which wait states this simulation applies
     *                 to, never {@code null}
     * @param action   the action to perform when a wait state matches, never {@code null}
     * @throws NullPointerException if {@code selector} or {@code action} is {@code null}
     */
    public Simulation(WaitStateSelector selector, Action action) {
        this(SimulationId.newId(), selector, action, DEFAULT_PRIORITY);
    }

    private Simulation(SimulationId id, WaitStateSelector selector, Action action, int priority) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        if (priority < 1) {
            throw new IllegalArgumentException("priority must be at least 1 (1 is highest): " + priority);
        }
        this.priority = priority;
    }

    /**
     * Returns this simulation's identity, stable across {@link #atPriority(int)} copies.
     *
     * @return the simulation id, never {@code null}
     */
    public SimulationId id() {
        return id;
    }

    /**
     * Returns the selector identifying which wait states this simulation applies to.
     *
     * @return the selector, never {@code null}
     */
    public WaitStateSelector selector() {
        return selector;
    }

    /**
     * Returns the action to perform when a wait state matches.
     *
     * @return the action, never {@code null}
     */
    public Action action() {
        return action;
    }

    /**
     * Returns this simulation's priority. Lower values take precedence; {@code 1} is
     * highest.
     *
     * @return the priority, always {@code >= 1}
     */
    public int priority() {
        return priority;
    }

    /**
     * Returns a copy of this simulation with a different priority, keeping the same
     * {@link #id()}, {@link #selector()}, and {@link #action()}.
     *
     * @param priority the new priority; must be at least {@code 1}
     * @return a new simulation with the given priority
     * @throws IllegalArgumentException if {@code priority} is less than {@code 1}
     */
    public Simulation atPriority(int priority) {
        return new Simulation(id, selector, action, priority);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Simulation other)) {
            return false;
        }
        return priority == other.priority
                && id.equals(other.id)
                && selector.equals(other.selector)
                && action.equals(other.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, selector, action, priority);
    }

    @Override
    public String toString() {
        return "Simulation[id=" + id + ", selector=" + selector + ", action=" + action + ", priority=" + priority
                + "]";
    }
}
