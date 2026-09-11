package io.github.tokennudge;

import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.testsupport.FakeEngineAdapter;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static io.github.tokennudge.TokenNudge.externalTask;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hammers {@code simulate}/{@code removeSimulation}/{@code reset}/{@code verify}/journal
 * reads from several threads while {@link NudgeLoop} keeps running against a
 * {@link FakeEngineAdapter} that keeps producing the same pool of wait states (recycled by
 * every {@code reset()}, which forgets what was already handled).
 *
 * <p>Round/{@link CyclicBarrier}-based rather than sleep-based: a barrier action performs
 * exactly one {@code reset()} between rounds, strictly after every worker thread has
 * finished registering/reading for the current round and strictly before any worker starts
 * the next round. This makes "no journal entry references a simulation registered before
 * the last reset" a deterministic, race-free assertion instead of a timing guess.
 */
class TokenNudgeConcurrencyTest {

    private static final String TOPIC = "concurrency-topic";
    private static final int WORKER_THREADS = 4;
    private static final int ROUNDS = 3;
    private static final int OPS_PER_ROUND = 12;
    private static final int WAIT_STATE_COUNT = 20;

    @RepeatedTest(100)
    void simulateResetVerifyAndJournalReadsFromSeveralThreadsNeverCorruptStateOrThrow() throws InterruptedException {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        for (int i = 0; i < WAIT_STATE_COUNT; i++) {
            adapter.addWaitState(new WaitState(
                    WaitStateKind.EXTERNAL_TASK, "task-" + i, TOPIC, "pi-" + i, "payment", "act", null, "ex-" + i,
                    null));
        }

        TokenNudge nudge = TokenNudge.forAdapter(adapter)
                .pollInterval(Duration.ofMillis(2))
                .requestTimeout(Duration.ofMillis(200))
                .verifyTimeout(Duration.ofMillis(100))
                .captureVariables(false)
                .start();

        Set<SimulationId> currentRoundIds = ConcurrentHashMap.newKeySet();
        List<Throwable> unexpectedErrors = new ArrayList<>();

        CyclicBarrier barrier = new CyclicBarrier(WORKER_THREADS, () -> {
            nudge.reset();
            currentRoundIds.clear();
        });

        Thread[] threads = new Thread[WORKER_THREADS];
        for (int t = 0; t < WORKER_THREADS; t++) {
            int threadIndex = t;
            threads[t] = new Thread(
                    () -> runWorker(nudge, currentRoundIds, unexpectedErrors, threadIndex, barrier),
                    "concurrency-worker-" + t);
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(thread.isAlive()).as("worker thread should have finished").isFalse();
        }

        assertThat(unexpectedErrors).isEmpty();

        Instant beforeStop = Instant.now();
        nudge.stop();
        assertThat(Duration.between(beforeStop, Instant.now())).isLessThan(Duration.ofSeconds(5));
        assertThat(nudge.isRunning()).isFalse();
    }

    private void runWorker(
            TokenNudge nudge,
            Set<SimulationId> currentRoundIds,
            List<Throwable> unexpectedErrors,
            int threadIndex,
            CyclicBarrier barrier) {
        try {
            for (int round = 0; round < ROUNDS; round++) {
                for (int op = 0; op < OPS_PER_ROUND; op++) {
                    performOp(nudge, currentRoundIds, (threadIndex + op) % 5);
                }
                barrier.await(3, TimeUnit.SECONDS);
            }
        } catch (Throwable t) {
            synchronized (unexpectedErrors) {
                unexpectedErrors.add(t);
            }
        }
    }

    private void performOp(TokenNudge nudge, Set<SimulationId> currentRoundIds, int opKind) {
        switch (opKind) {
            case 0 -> {
                Simulation simulation = externalTask(TOPIC).willComplete();
                currentRoundIds.add(simulation.id());
                nudge.simulate(simulation);
            }
            case 1 -> nudge.verify(externalTask(TOPIC).completed().atLeast(0));
            case 2 -> checkJournalSnapshot(nudge.journal(), currentRoundIds);
            case 3 -> {
                SimulationId toRemove = currentRoundIds.stream().findAny().orElse(null);
                if (toRemove != null) {
                    nudge.removeSimulation(toRemove);
                }
            }
            default -> {
                assertThat(nudge.actionErrors()).isEmpty();
                assertThat(nudge.unmatched()).isEmpty();
            }
        }
    }

    private void checkJournalSnapshot(List<JournalEntry> entries, Set<SimulationId> currentRoundIds) {
        long previousSequence = -1;
        for (JournalEntry entry : entries) {
            assertThat(entry.sequence()).isGreaterThan(previousSequence);
            previousSequence = entry.sequence();
            entry.simulationId().ifPresent(
                    id -> assertThat(currentRoundIds)
                            .as("journal entry must not reference a simulation from before the last reset")
                            .contains(id));
        }
    }
}
