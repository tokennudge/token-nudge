package io.github.tokennudge;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Test fixture factory for {@link JournalEntry} instances (not a test class itself).
 */
final class JournalEntries {

    private static long sequence = 0;

    private JournalEntries() {
    }

    static WaitState externalTask(String id, String topic) {
        return new WaitState(
                WaitStateKind.EXTERNAL_TASK, id, topic, "pi-1", "payment", "act", "order-42", "ex-1", null);
    }

    static JournalEntry entry(WaitState waitState, Outcome outcome, Action action, Map<String, Object> variables) {
        return new JournalEntry(
                sequence++,
                Instant.now(),
                waitState,
                Optional.of(SimulationId.newId()),
                Optional.ofNullable(action),
                outcome,
                variables,
                Optional.empty());
    }

    static JournalEntry unmatched(WaitState waitState) {
        return new JournalEntry(
                sequence++, Instant.now(), waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED,
                Map.of(), Optional.empty());
    }
}
