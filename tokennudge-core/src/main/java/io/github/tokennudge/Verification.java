package io.github.tokennudge;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.CompleteUserTask;
import io.github.tokennudge.model.CorrelateMessage;
import io.github.tokennudge.model.EqualsVariableMatcher;
import io.github.tokennudge.model.FailExternalTask;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.model.ThrowBpmnError;
import io.github.tokennudge.model.VariableMatcher;
import io.github.tokennudge.model.WaitStateSelector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * An assertion that a wait state was reached / handled / completed a certain number of
 * times, optionally filtered by the process variables captured at the wait state, and
 * awaited for up to a timeout.
 *
 * <p>Instances are immutable; every builder method ({@link #times(int)},
 * {@link #atLeast(int)}, {@link #atMost(int)}, {@link #never()}, {@link #withVariable}, and
 * {@link #within}) returns a new instance. The default count expectation, if none of
 * {@code times}/{@code atLeast}/{@code atMost}/{@code never} is called, is
 * {@code atLeast(1)}.
 *
 * <p>Created via terminal methods on {@link WaitStateSpec} and its subtypes (for example
 * {@link ExternalTaskSpec#completed()}), and evaluated by the package-private
 * {@link VerificationEvaluator}. The count/outcome/near-miss inspection methods
 * {@code VerificationEvaluator} needs (for example {@link #matches(JournalEntry)}) are
 * themselves package-private: this class's public surface is the fluent DSL only.
 */
public final class Verification {

    private final WaitStateSelector selector;
    private final Predicate<JournalEntry> outcomeFilter;
    private final String outcomeDescription;
    private final List<VariableMatcher> variableFilters;
    private final CountExpectation countExpectation;
    private final Duration timeout;

    private Verification(
            WaitStateSelector selector,
            Predicate<JournalEntry> outcomeFilter,
            String outcomeDescription,
            List<VariableMatcher> variableFilters,
            CountExpectation countExpectation,
            Duration timeout) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.outcomeFilter = Objects.requireNonNull(outcomeFilter, "outcomeFilter must not be null");
        this.outcomeDescription = Objects.requireNonNull(outcomeDescription, "outcomeDescription must not be null");
        this.variableFilters = List.copyOf(variableFilters);
        this.countExpectation = Objects.requireNonNull(countExpectation, "countExpectation must not be null");
        this.timeout = timeout;
    }

    /**
     * A verification that a wait state was observed at all, whatever the outcome.
     *
     * @param selector the selector identifying the wait state, never {@code null}
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    static Verification reached(WaitStateSelector selector) {
        return initial(selector, entry -> true, "reached");
    }

    /**
     * A verification that a wait state was handled, i.e. a simulation matched it and its
     * action executed successfully.
     *
     * @param selector the selector identifying the wait state, never {@code null}
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    static Verification handled(WaitStateSelector selector) {
        return initial(selector, entry -> entry.outcome() == Outcome.HANDLED, "handled");
    }

    /**
     * A verification that an external task was completed successfully.
     *
     * @param selector the selector identifying the external task, never {@code null}
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    static Verification completedExternalTask(WaitStateSelector selector) {
        return initial(selector, handledWithAction(CompleteExternalTask.class::isInstance), "completed");
    }

    /**
     * A verification that an external task failed with a BPMN error.
     *
     * @param selector  the selector identifying the external task, never {@code null}
     * @param errorCode the required error code, or {@code null} to accept any error code
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    static Verification failedWithBpmnError(WaitStateSelector selector, String errorCode) {
        Predicate<Action> actionFilter = action -> action instanceof ThrowBpmnError thrown
                && (errorCode == null || errorCode.equals(thrown.errorCode()));
        String description = errorCode == null ? "failed with a BPMN error" : "failed with BPMN error " + errorCode;
        return initial(selector, handledWithAction(actionFilter), description);
    }

    /**
     * A verification that an external task failed technically (not with a BPMN error).
     *
     * @param selector the selector identifying the external task, never {@code null}
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    static Verification failedExternalTask(WaitStateSelector selector) {
        return initial(selector, handledWithAction(FailExternalTask.class::isInstance), "failed");
    }

    /**
     * A verification that a user task was completed successfully.
     *
     * @param selector the selector identifying the user task, never {@code null}
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    static Verification completedUserTask(WaitStateSelector selector) {
        return initial(selector, handledWithAction(CompleteUserTask.class::isInstance), "completed");
    }

    /**
     * A verification that a message wait state was correlated successfully.
     *
     * @param selector the selector identifying the message wait state, never {@code null}
     * @return a new verification with the default expectation of {@code atLeast(1)}
     */
    static Verification correlatedMessage(WaitStateSelector selector) {
        return initial(selector, handledWithAction(CorrelateMessage.class::isInstance), "correlated");
    }

    private static Predicate<JournalEntry> handledWithAction(Predicate<Action> actionFilter) {
        return entry -> entry.outcome() == Outcome.HANDLED && entry.action().filter(actionFilter).isPresent();
    }

    private static Verification initial(
            WaitStateSelector selector, Predicate<JournalEntry> outcomeFilter, String outcomeDescription) {
        return new Verification(
                selector, outcomeFilter, outcomeDescription, List.of(), CountExpectation.atLeast(1), null);
    }

    /**
     * Requires the wait state to have been matched exactly {@code n} times.
     *
     * <p>This is a monotonic expectation: while awaiting, it fails as soon as the count
     * exceeds {@code n} (fail-fast), rather than waiting for the full timeout.
     *
     * @param n the exact required count; must not be negative
     * @return a new verification with the given expectation
     * @throws IllegalArgumentException if {@code n} is negative
     */
    public Verification times(int n) {
        return withCount(CountExpectation.exactly(n));
    }

    /**
     * Requires the wait state to have been matched at least {@code n} times. This is the
     * default expectation if none of {@code times}/{@code atLeast}/{@code atMost}/
     * {@code never} is called (with {@code n == 1}).
     *
     * <p>This is a monotonic expectation: awaiting stops as soon as the count reaches
     * {@code n}.
     *
     * @param n the minimum required count; must not be negative
     * @return a new verification with the given expectation
     * @throws IllegalArgumentException if {@code n} is negative
     */
    public Verification atLeast(int n) {
        return withCount(CountExpectation.atLeast(n));
    }

    /**
     * Requires the wait state to have been matched at most {@code n} times.
     *
     * <p>This is a non-monotonic expectation: it is evaluated once, after waiting for one
     * full loop iteration that started after the verification began, rather than as soon
     * as it becomes true (which would be immediately).
     *
     * @param n the maximum allowed count; must not be negative
     * @return a new verification with the given expectation
     * @throws IllegalArgumentException if {@code n} is negative
     */
    public Verification atMost(int n) {
        return withCount(CountExpectation.atMost(n));
    }

    /**
     * Requires the wait state to never have been matched. Equivalent to {@code atMost(0)}.
     *
     * @return a new verification with the given expectation
     */
    public Verification never() {
        return withCount(CountExpectation.atMost(0));
    }

    private Verification withCount(CountExpectation countExpectation) {
        return new Verification(selector, outcomeFilter, outcomeDescription, variableFilters, countExpectation, timeout);
    }

    /**
     * Adds a filter requiring the named variable, as captured at the wait state (not as
     * submitted by any action), to equal the given value.
     *
     * <p>Equality is numeric-normalized, as for {@link WaitStateSpec#withVariable}.
     *
     * @param name          the variable name, never {@code null}
     * @param expectedValue the expected value, may be {@code null}
     * @return a new verification with the additional filter
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Verification withVariable(String name, Object expectedValue) {
        Objects.requireNonNull(name, "name must not be null");
        List<VariableMatcher> filters = new ArrayList<>(variableFilters);
        filters.add(new EqualsVariableMatcher(name, expectedValue));
        return new Verification(selector, outcomeFilter, outcomeDescription, filters, countExpectation, timeout);
    }

    /**
     * Overrides the default timeout used while awaiting this verification.
     *
     * @param timeout the timeout, never {@code null} or negative
     * @return a new verification with the given timeout
     * @throws NullPointerException     if {@code timeout} is {@code null}
     * @throws IllegalArgumentException if {@code timeout} is negative
     */
    public Verification within(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        return new Verification(selector, outcomeFilter, outcomeDescription, variableFilters, countExpectation, timeout);
    }

    /**
     * Returns the timeout explicitly set via {@link #within(Duration)}, if any.
     *
     * <p>Package-private: intended for {@link VerificationEvaluator}; when {@code null},
     * the evaluator falls back to a caller-supplied default timeout.
     *
     * @return the explicit timeout, or {@code null} if {@link #within(Duration)} was never
     *         called
     */
    Duration explicitTimeout() {
        return timeout;
    }

    /**
     * Returns whether this verification's count expectation is monotonic ({@code times}/
     * {@code atLeast}: the matching count only grows, so awaiting can stop as soon as the
     * expectation is met) or not ({@code atMost}/{@code never}: awaiting must instead wait
     * for one full iteration and then evaluate once).
     *
     * <p>Package-private: intended for {@link VerificationEvaluator}.
     *
     * @return {@code true} if the count expectation is monotonic
     */
    boolean isMonotonic() {
        return countExpectation.isMonotonic();
    }

    /**
     * Returns whether the given match count already satisfies this verification's count
     * expectation.
     *
     * <p>Package-private: intended for {@link VerificationEvaluator}.
     *
     * @param matchCount the number of matching journal entries observed so far
     * @return {@code true} if {@code matchCount} satisfies the expectation
     */
    boolean isSatisfiedBy(long matchCount) {
        return countExpectation.isSatisfiedBy(matchCount);
    }

    /**
     * Returns whether the given match count already exceeds what this verification could
     * ever accept, allowing a monotonic expectation to fail fast instead of waiting for the
     * timeout.
     *
     * <p>Package-private: intended for {@link VerificationEvaluator}.
     *
     * @param matchCount the number of matching journal entries observed so far
     * @return {@code true} if {@code matchCount} can never satisfy the expectation, even if
     *         more matches occur
     */
    boolean isExceededBy(long matchCount) {
        return countExpectation.isExceededBy(matchCount);
    }

    /**
     * Returns a short, human-readable description of the count expectation (for example
     * {@code "at least 1"}, {@code "exactly 2"}, {@code "never"}), for diagnostic messages.
     *
     * <p>Package-private: intended for {@link VerificationEvaluator}.
     *
     * @return the description, never {@code null}
     */
    String describeExpectation() {
        return outcomeDescription + " " + countExpectation.describe();
    }

    /**
     * Evaluates whether a journal entry's wait state matches this verification's selector
     * (kind, name, and any static/variable constraints), regardless of outcome. Used to
     * find "near miss" entries for diagnostic messages when a verification fails.
     *
     * <p>Package-private: intended for {@link VerificationEvaluator}.
     *
     * @param entry the journal entry to test, never {@code null}
     * @return {@code true} if the entry's wait state and variables match this
     *         verification's selector
     * @throws NullPointerException if {@code entry} is {@code null}
     */
    boolean matchesWaitState(JournalEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        return selector.matchesStatic(entry.waitState()) && selector.matchesVariables(entry.variablesAtWaitState());
    }

    /**
     * Evaluates whether a journal entry fully satisfies this verification: its wait state
     * matches (see {@link #matchesWaitState(JournalEntry)}), its outcome/action satisfies
     * the verification terminal (for example {@code completed()}), and every filter added
     * via {@link #withVariable} passes.
     *
     * <p>Package-private: intended for {@link VerificationEvaluator}.
     *
     * @param entry the journal entry to test, never {@code null}
     * @return {@code true} if the entry fully satisfies this verification
     * @throws NullPointerException if {@code entry} is {@code null}
     */
    boolean matches(JournalEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        return matchesWaitState(entry)
                && outcomeFilter.test(entry)
                && variableFilters.stream().allMatch(matcher -> matcher.matches(entry.variablesAtWaitState()));
    }

    /**
     * The count expectation ({@code times}/{@code atLeast}/{@code atMost}/{@code never}) of
     * a {@link Verification}.
     */
    private enum CountKind {
        AT_LEAST,
        AT_MOST,
        EXACTLY
    }

    private record CountExpectation(CountKind kind, int value) {

        private CountExpectation {
            if (value < 0) {
                throw new IllegalArgumentException("count must not be negative: " + value);
            }
        }

        static CountExpectation atLeast(int n) {
            return new CountExpectation(CountKind.AT_LEAST, n);
        }

        static CountExpectation atMost(int n) {
            return new CountExpectation(CountKind.AT_MOST, n);
        }

        static CountExpectation exactly(int n) {
            return new CountExpectation(CountKind.EXACTLY, n);
        }

        boolean isMonotonic() {
            return kind != CountKind.AT_MOST;
        }

        boolean isSatisfiedBy(long count) {
            return switch (kind) {
                case AT_LEAST -> count >= value;
                case AT_MOST -> count <= value;
                case EXACTLY -> count == value;
            };
        }

        boolean isExceededBy(long count) {
            return kind == CountKind.EXACTLY && count > value;
        }

        String describe() {
            return switch (kind) {
                case AT_LEAST -> "at least " + value;
                case AT_MOST -> value == 0 ? "never" : "at most " + value;
                case EXACTLY -> "exactly " + value;
            };
        }
    }
}
