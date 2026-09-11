package io.github.tokennudge.internal;

import io.github.tokennudge.SimulationId;
import io.github.tokennudge.Variables;
import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.CompleteExternalTask;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class InMemoryJournalTest {

    private static WaitState waitState(String id) {
        return new WaitState(
                WaitStateKind.EXTERNAL_TASK, id, "charge-card", "pi-1", "payment", "act", "bk", "ex-1", null);
    }

    private static Optional<Action> completeAction() {
        return Optional.of(new CompleteExternalTask(Variables.empty()));
    }

    @Test
    void appendReturnsTheRecordedEntry() {
        InMemoryJournal journal = new InMemoryJournal();

        Optional<JournalEntry> entry = journal.append(
                waitState("task-1"),
                Optional.of(SimulationId.newId()),
                completeAction(),
                Outcome.HANDLED,
                Map.of("amount", 4200),
                Optional.empty());

        assertThat(entry).isPresent();
        assertThat(entry.get().waitState().id()).isEqualTo("task-1");
        assertThat(entry.get().outcome()).isEqualTo(Outcome.HANDLED);
    }

    @Test
    void sequenceNumbersAreMonotonicallyIncreasing() {
        InMemoryJournal journal = new InMemoryJournal();

        JournalEntry first = journal.append(
                        waitState("task-1"), Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(),
                        Optional.empty())
                .orElseThrow();
        JournalEntry second = journal.append(
                        waitState("task-2"), Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(),
                        Optional.empty())
                .orElseThrow();

        assertThat(second.sequence()).isGreaterThan(first.sequence());
    }

    @Test
    void unmatchedEntriesAreDeduplicatedByWaitStateId() {
        InMemoryJournal journal = new InMemoryJournal();
        WaitState waitState = waitState("task-1");

        Optional<JournalEntry> first = journal.append(
                waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty());
        Optional<JournalEntry> second = journal.append(
                waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty());

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(journal.entries()).hasSize(1);
    }

    @Test
    void unmatchedDeduplicationIsPerWaitStateId() {
        InMemoryJournal journal = new InMemoryJournal();

        journal.append(waitState("task-1"), Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty());
        journal.append(waitState("task-2"), Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty());

        assertThat(journal.entries()).hasSize(2);
    }

    @Test
    void nonUnmatchedOutcomesAreNeverDeduplicated() {
        InMemoryJournal journal = new InMemoryJournal();
        WaitState waitState = waitState("task-1");

        journal.append(waitState, Optional.of(SimulationId.newId()), completeAction(), Outcome.HANDLED, Map.of(), Optional.empty());
        journal.append(waitState, Optional.of(SimulationId.newId()), completeAction(), Outcome.HANDLED, Map.of(), Optional.empty());

        assertThat(journal.entries()).hasSize(2);
    }

    @Test
    void entriesReturnsACopyNotALiveView() {
        InMemoryJournal journal = new InMemoryJournal();
        journal.append(waitState("task-1"), Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty());

        List<JournalEntry> snapshot = journal.entries();
        journal.append(waitState("task-2"), Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty());

        assertThat(snapshot).hasSize(1);
    }

    @Test
    void resetClearsEntriesAndUnmatchedDeduplicationMemory() {
        InMemoryJournal journal = new InMemoryJournal();
        WaitState waitState = waitState("task-1");
        journal.append(waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty());

        journal.reset();

        assertThat(journal.entries()).isEmpty();
        Optional<JournalEntry> afterReset = journal.append(
                waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty());
        assertThat(afterReset).isPresent();
    }

    @Test
    void appendRejectsNullArguments() {
        InMemoryJournal journal = new InMemoryJournal();
        WaitState waitState = waitState("task-1");

        assertThatNullPointerException().isThrownBy(() -> journal.append(
                null, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> journal.append(
                waitState, null, Optional.empty(), Outcome.UNMATCHED, Map.of(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> journal.append(
                waitState, Optional.empty(), null, Outcome.UNMATCHED, Map.of(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> journal.append(
                waitState, Optional.empty(), Optional.empty(), null, Map.of(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> journal.append(
                waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, null, Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> journal.append(
                waitState, Optional.empty(), Optional.empty(), Outcome.UNMATCHED, Map.of(), null));
    }

    @Test
    void concurrentAppendsAreAllRecordedWithUniqueSequences() throws InterruptedException {
        InMemoryJournal journal = new InMemoryJournal();
        int threadCount = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threadCount; t++) {
            int threadIndex = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < perThread; i++) {
                    journal.append(
                            waitState("task-" + threadIndex + "-" + i),
                            Optional.of(SimulationId.newId()),
                            completeAction(),
                            Outcome.HANDLED,
                            Map.of(),
                            Optional.empty());
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<JournalEntry> entries = journal.entries();
        assertThat(entries).hasSize(threadCount * perThread);
        long distinctSequences = entries.stream().map(JournalEntry::sequence).distinct().count();
        assertThat(distinctSequences).isEqualTo(entries.size());

        List<Long> sortedSequences = entries.stream().map(JournalEntry::sequence).sorted().toList();
        List<Long> expected = IntStream.range(0, entries.size()).mapToObj(i -> (long) i).toList();
        assertThat(sortedSequences).isEqualTo(expected);
    }
}
