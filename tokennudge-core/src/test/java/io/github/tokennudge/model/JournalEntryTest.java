package io.github.tokennudge.model;

import io.github.tokennudge.SimulationId;
import io.github.tokennudge.Variables;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private static JournalEntry handledEntry(Map<String, Object> variables) {
        return new JournalEntry(
                1,
                Instant.now(),
                WaitStates.externalTask(),
                Optional.of(SimulationId.newId()),
                Optional.of(new CompleteExternalTask(Variables.empty())),
                Outcome.HANDLED,
                variables,
                Optional.empty());
    }

    @Test
    void exposesGivenFields() {
        SimulationId simulationId = SimulationId.newId();
        Action action = new CompleteExternalTask(Variables.empty());
        Instant now = Instant.now();
        WaitState waitState = WaitStates.externalTask();

        JournalEntry entry = new JournalEntry(
                7,
                now,
                waitState,
                Optional.of(simulationId),
                Optional.of(action),
                Outcome.HANDLED,
                Map.of("amount", 4200),
                Optional.empty());

        assertThat(entry.sequence()).isEqualTo(7);
        assertThat(entry.timestamp()).isEqualTo(now);
        assertThat(entry.waitState()).isEqualTo(waitState);
        assertThat(entry.simulationId()).contains(simulationId);
        assertThat(entry.action()).contains(action);
        assertThat(entry.outcome()).isEqualTo(Outcome.HANDLED);
        assertThat(entry.variablesAtWaitState()).containsEntry("amount", 4200);
        assertThat(entry.error()).isEmpty();
    }

    @Test
    void unmatchedEntryHasEmptySimulationAndAction() {
        JournalEntry entry = new JournalEntry(
                1,
                Instant.now(),
                WaitStates.externalTask(),
                Optional.empty(),
                Optional.empty(),
                Outcome.UNMATCHED,
                Map.of(),
                Optional.empty());

        assertThat(entry.simulationId()).isEmpty();
        assertThat(entry.action()).isEmpty();
    }

    @Test
    void actionFailedEntryCarriesErrorMessage() {
        JournalEntry entry = new JournalEntry(
                1,
                Instant.now(),
                WaitStates.externalTask(),
                Optional.of(SimulationId.newId()),
                Optional.of(new CompleteExternalTask(Variables.empty())),
                Outcome.ACTION_FAILED,
                Map.of(),
                Optional.of("engine rejected the action"));

        assertThat(entry.error()).contains("engine rejected the action");
    }

    @Test
    void negativeSequenceIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new JournalEntry(
                -1,
                Instant.now(),
                WaitStates.externalTask(),
                Optional.empty(),
                Optional.empty(),
                Outcome.UNMATCHED,
                Map.of(),
                Optional.empty()));
    }

    @Test
    void requiredFieldsMustNotBeNull() {
        WaitState waitState = WaitStates.externalTask();
        Instant now = Instant.now();

        assertThatNullPointerException().isThrownBy(() -> new JournalEntry(
                1, null, waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(),
                Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new JournalEntry(
                1, now, null, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new JournalEntry(
                1, now, waitState, null, Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new JournalEntry(
                1, now, waitState, Optional.empty(), null, Outcome.UNMATCHED, Map.of(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new JournalEntry(
                1, now, waitState, Optional.empty(), Optional.empty(), null, Map.of(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new JournalEntry(
                1, now, waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, null, Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new JournalEntry(
                1, now, waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), null));
    }

    @Test
    void variablesAtWaitStateAllowsNullValuesAndIsDefensivelyCopied() {
        Map<String, Object> source = new HashMap<>();
        source.put("comment", null);
        source.put("amount", 100);

        JournalEntry entry = handledEntry(source);
        source.put("amount", 200);

        assertThat(entry.variablesAtWaitState()).containsEntry("comment", null);
        assertThat(entry.variablesAtWaitState()).containsEntry("amount", 100);
    }

    @Test
    void variablesAtWaitStateIsUnmodifiable() {
        JournalEntry entry = handledEntry(Map.of("amount", 100));

        assertThatThrownBy(() -> entry.variablesAtWaitState().put("other", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void variablesAtWaitStateDeepCopiesNestedContainersAndByteArrays() {
        byte[] payload = {1, 2, 3};
        Map<String, Object> nested = new HashMap<>();
        nested.put("payload", payload);
        Map<String, Object> source = new HashMap<>();
        source.put("nested", nested);

        JournalEntry entry = handledEntry(source);

        payload[0] = 42;
        nested.put("payload", "replaced");

        @SuppressWarnings("unchecked")
        Map<String, Object> storedNested = (Map<String, Object>) entry.variablesAtWaitState().get("nested");
        assertThat((byte[]) storedNested.get("payload")).containsExactly(1, 2, 3);
    }

    @Test
    void variablesAtWaitStateEachCallReturnsAnIndependentCopy() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("k", "v");
        JournalEntry entry = handledEntry(Map.of("nested", nested));

        @SuppressWarnings("unchecked")
        Map<String, Object> firstRead = (Map<String, Object>) entry.variablesAtWaitState().get("nested");
        assertThatThrownBy(() -> firstRead.put("k", "mutated")).isInstanceOf(UnsupportedOperationException.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> secondRead = (Map<String, Object>) entry.variablesAtWaitState().get("nested");
        assertThat(secondRead).containsEntry("k", "v");
    }
}
