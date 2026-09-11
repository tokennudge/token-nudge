package io.github.tokennudge;

import io.github.tokennudge.internal.InMemoryJournal;
import io.github.tokennudge.model.JournalEntry;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates a {@link Verification} against an {@link InMemoryJournal}, awaiting loop
 * iterations via an {@link IterationClock} as needed.
 *
 * <p>Await semantics (see the plan's "Verify and awaiting" design note):
 * <ul>
 *   <li>If {@link IterationClock#isRunning()} is {@code false}, or the calling thread is
 *       the loop's own thread (see {@link IterationClock#isCurrentThreadTheLoopThread()}),
 *       the verification is evaluated immediately, once.</li>
 *   <li>Monotonic expectations ({@code times}/{@code atLeast}, see
 *       {@link Verification#isMonotonic()}) are re-evaluated after every loop iteration
 *       until satisfied or the deadline passes; {@code times(n)} fails fast as soon as the
 *       match count exceeds {@code n} (see {@link Verification#isExceededBy(long)}),
 *       without waiting for the deadline.</li>
 *   <li>Non-monotonic expectations ({@code atMost}/{@code never}) wait for one full
 *       iteration that starts after the call to {@link #evaluate(Verification, Duration)}
 *       (see {@link IterationClock#freshIterationBaseline(Duration)}), then are evaluated
 *       exactly once.</li>
 * </ul>
 *
 * <p>Package-private: not public API. Lives alongside {@link Verification} and
 * {@link TokenNudge} (rather than under {@code io.github.tokennudge.internal}, where the
 * plan originally placed it) so that the count/outcome/near-miss inspection methods it
 * needs on {@link Verification} can stay package-private too, instead of being forced
 * public purely to cross a package boundary. See {@code docs/PLAN.md} section 2.1 for this
 * deviation.
 */
final class VerificationEvaluator {

    private static final int MAX_NEAR_MISSES = 5;

    private final InMemoryJournal journal;
    private final IterationClock clock;

    /**
     * Creates a new evaluator.
     *
     * @param journal the journal to evaluate verifications against, never {@code null}
     * @param clock   the clock used to await loop iterations, never {@code null}
     */
    VerificationEvaluator(InMemoryJournal journal, IterationClock clock) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Evaluates a verification, awaiting loop iterations as needed.
     *
     * @param verification   the verification to evaluate, never {@code null}
     * @param defaultTimeout the timeout to use if {@code verification} did not set one via
     *                       {@code within(...)}, never {@code null}
     * @throws NullPointerException   if {@code verification} or {@code defaultTimeout} is
     *                                {@code null}
     * @throws VerificationException if the verification is not satisfied within the
     *                                effective timeout (or immediately, if the loop is not
     *                                running)
     */
    void evaluate(Verification verification, Duration defaultTimeout) {
        Objects.requireNonNull(verification, "verification must not be null");
        Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");

        Duration timeout = verification.explicitTimeout() != null ? verification.explicitTimeout() : defaultTimeout;

        if (!clock.isRunning() || clock.isCurrentThreadTheLoopThread()) {
            assertSatisfied(verification);
            return;
        }

        if (verification.isMonotonic()) {
            awaitMonotonic(verification, timeout);
        } else {
            awaitNonMonotonic(verification, timeout);
        }
    }

    private void awaitMonotonic(Verification verification, Duration timeout) {
        long deadline = saturatingAdd(clock.nanoTime(), timeout.toNanos());
        while (true) {
            List<JournalEntry> matches = matchingEntries(verification);
            if (verification.isExceededBy(matches.size())) {
                throw failure(verification, matches);
            }
            if (verification.isSatisfiedBy(matches.size())) {
                return;
            }
            if (clock.nanoTime() >= deadline) {
                throw failure(verification, matches);
            }
            clock.awaitIterationAfter(clock.iterationCount(), deadline);
        }
    }

    private void awaitNonMonotonic(Verification verification, Duration timeout) {
        long deadline = saturatingAdd(clock.nanoTime(), timeout.toNanos());
        // Read while the deadline still applies: freshIterationBaseline() may itself block
        // (briefly) to obtain a baseline that is guaranteed fresh; see its Javadoc.
        long startIteration = clock.freshIterationBaseline(remainingTimeUntil(deadline));
        clock.awaitIterationAfter(startIteration, deadline);
        assertSatisfied(verification);
    }

    private Duration remainingTimeUntil(long deadlineNanos) {
        return Duration.ofNanos(Math.max(deadlineNanos - clock.nanoTime(), 0));
    }

    /**
     * Adds {@code a} and {@code b}, clamping to {@link Long#MAX_VALUE} instead of wrapping
     * around on overflow. {@link IterationClock#nanoTime()} is not guaranteed to leave
     * enough headroom below {@link Long#MAX_VALUE} to add an arbitrary timeout without
     * overflowing a plain {@code long} addition.
     */
    private static long saturatingAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private void assertSatisfied(Verification verification) {
        List<JournalEntry> matches = matchingEntries(verification);
        if (!verification.isSatisfiedBy(matches.size())) {
            throw failure(verification, matches);
        }
    }

    private List<JournalEntry> matchingEntries(Verification verification) {
        return journal.entries().stream().filter(verification::matches).toList();
    }

    private VerificationException failure(Verification verification, List<JournalEntry> matches) {
        List<JournalEntry> nearMisses = journal.entries().stream()
                .filter(verification::matchesWaitState)
                .filter(entry -> !verification.matches(entry))
                .sorted(Comparator.comparingLong(JournalEntry::sequence).reversed())
                .limit(MAX_NEAR_MISSES)
                .toList();
        return new VerificationException(buildMessage(verification, matches, nearMisses));
    }

    private String buildMessage(Verification verification, List<JournalEntry> matches, List<JournalEntry> nearMisses) {
        StringBuilder message = new StringBuilder();
        message.append("Expected wait state to be ")
                .append(verification.describeExpectation())
                .append(" time(s), but it was ")
                .append(matches.size())
                .append(" time(s).");

        message.append("\nMatching entries: ");
        message.append(matches.isEmpty() ? "none." : "");
        for (JournalEntry entry : matches) {
            message.append("\n  - ").append(describe(entry));
        }

        message.append("\nNear misses: ");
        message.append(nearMisses.isEmpty() ? "none." : "");
        for (JournalEntry entry : nearMisses) {
            message.append("\n  - ").append(describe(entry));
        }
        return message.toString();
    }

    private String describe(JournalEntry entry) {
        StringBuilder description = new StringBuilder();
        description
                .append("sequence=")
                .append(entry.sequence())
                .append(", waitState=")
                .append(entry.waitState().id())
                .append(", outcome=")
                .append(entry.outcome());
        entry.action().ifPresent(action -> description.append(", action=").append(action));
        description.append(", variables=").append(entry.variablesAtWaitState());
        entry.error().ifPresent(error -> description.append(", error=").append(error));
        return description.toString();
    }
}
