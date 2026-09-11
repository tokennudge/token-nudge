package io.github.tokennudge;

import io.github.tokennudge.model.JournalEntry;

import java.util.List;

/**
 * The operations exposed by {@link TokenNudge} and (in a later iteration) the JUnit 5
 * extension: registering/removing simulations, verifying and inspecting the journal, and
 * resetting state between tests.
 */
public interface NudgeOperations {

    /**
     * Registers a simulation.
     *
     * @param simulation the simulation to register, never {@code null}
     * @return the registered simulation's id
     * @throws NullPointerException if {@code simulation} is {@code null}
     */
    SimulationId simulate(Simulation simulation);

    /**
     * Removes a previously registered simulation.
     *
     * @param id the id of the simulation to remove, never {@code null}
     * @return {@code true} if a simulation was removed
     * @throws NullPointerException if {@code id} is {@code null}
     */
    boolean removeSimulation(SimulationId id);

    /**
     * Returns a snapshot of the currently registered simulations, in precedence order.
     *
     * @return an immutable, ordered snapshot; never {@code null}
     */
    List<Simulation> simulations();

    /**
     * Evaluates a verification, awaiting loop iterations as needed (or evaluating
     * immediately if the loop is not running).
     *
     * @param verification the verification to evaluate, never {@code null}
     * @throws NullPointerException  if {@code verification} is {@code null}
     * @throws VerificationException if the verification is not satisfied
     */
    void verify(Verification verification);

    /**
     * Returns a copy of every journal entry recorded so far.
     *
     * @return an immutable, ordered snapshot; never {@code null}
     */
    List<JournalEntry> journal();

    /**
     * Returns the journal entries whose outcome is
     * {@link io.github.tokennudge.model.Outcome#ACTION_FAILED}.
     *
     * @return an immutable, ordered snapshot; never {@code null}
     */
    List<JournalEntry> actionErrors();

    /**
     * Returns the journal entries whose outcome is
     * {@link io.github.tokennudge.model.Outcome#UNMATCHED}.
     *
     * @return an immutable, ordered snapshot; never {@code null}
     */
    List<JournalEntry> unmatched();

    /**
     * Clears registered simulations, the journal, and internal handled-wait-state memory.
     * Never changes engine state. Waits for any in-flight loop iteration to finish first.
     */
    void reset();

    /**
     * Clears the journal only, leaving registered simulations in place. Never changes
     * engine state. Waits for any in-flight loop iteration to finish first.
     */
    void resetJournal();
}
