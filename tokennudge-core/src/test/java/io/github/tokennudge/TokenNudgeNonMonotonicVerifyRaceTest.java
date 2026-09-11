package io.github.tokennudge;

import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.testsupport.FakeEngineAdapter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.tokennudge.TokenNudge.externalTask;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces, deterministically and without a fixed sleep, the iteration-4 code review's
 * blocking finding: a non-monotonic {@code verify(...never())} must not treat an iteration
 * that had already started before the {@code verify()} call as "one full iteration that
 * started after the call" (see the plan's "Verify and awaiting" design note). If it did, a
 * {@code never()} could incorrectly pass even though the matching action is only handled by
 * the very next iteration, one the call never actually waited for.
 *
 * <p>Synchronization is purely via a {@link CountDownLatch} (to pin the loop's first
 * iteration inside {@link FakeEngineAdapter#discover}, guaranteeing it finds nothing) and by
 * spin-polling the verifying thread's {@link Thread.State} (to know, without a sleep, that it
 * has genuinely captured its baseline and is now blocked awaiting a fresh iteration, before
 * that first, already-in-flight iteration is allowed to complete).
 */
class TokenNudgeNonMonotonicVerifyRaceTest {

    private static final String TOPIC = "charge-card";
    private static final Set<Thread.State> BLOCKED_STATES =
            Set.of(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED);

    private static WaitState waitState(String id) {
        return new WaitState(WaitStateKind.EXTERNAL_TASK, id, TOPIC, "pi-1", "payment", "act", null, "ex-1", null);
    }

    @Test
    void neverDoesNotPassBasedOnAnIterationThatHadAlreadyStartedBeforeVerifyWasCalled() throws Exception {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        CountDownLatch discoveryEntered = new CountDownLatch(1);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        adapter.blockNextDiscovery(discoveryEntered, releaseDiscovery);

        TokenNudge nudge = TokenNudge.forAdapter(adapter)
                .pollInterval(Duration.ofMillis(20))
                .verifyTimeout(Duration.ofSeconds(5))
                .build();
        nudge.simulate(externalTask(TOPIC).willComplete());
        nudge.start();

        try {
            // The loop's first iteration is now blocked inside discover(): it has already
            // started, but has not yet decided what it will discover.
            assertThat(discoveryEntered.await(5, TimeUnit.SECONDS))
                    .as("the loop's first iteration should have entered discover() by now")
                    .isTrue();

            Throwable[] outcome = new Throwable[1];
            Thread verifierThread = new Thread(() -> {
                try {
                    nudge.verify(externalTask(TOPIC).completed().never());
                } catch (Throwable t) {
                    outcome[0] = t;
                }
            }, "verifier");
            verifierThread.start();

            // Wait until the verifying thread is genuinely blocked -- either awaiting the
            // iteration lock (the fix) or the iteration condition directly (the pre-fix
            // code) -- guaranteeing it already captured its baseline before the in-flight
            // iteration below is allowed to complete. No fixed sleep: this spins on the
            // thread's actual state.
            awaitThreadBlocked(verifierThread, Duration.ofSeconds(5));

            // Let the already-in-flight iteration finish. It discovers nothing (the wait
            // state below does not exist yet), so it does no work.
            releaseDiscovery.countDown();

            // Only now does the wait state start existing. A correct implementation may
            // only let a genuinely fresh iteration (one that starts after verify() was
            // called) observe it.
            adapter.addWaitState(waitState("task-1"));

            verifierThread.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(verifierThread.isAlive()).as("verifying thread should have finished").isFalse();
            assertThat(outcome[0])
                    .as("never() must fail: the wait state was handled by the iteration right after the "
                            + "one already in flight when verify() was called")
                    .isInstanceOf(VerificationException.class);
        } finally {
            nudge.stop();
        }
    }

    private static void awaitThreadBlocked(Thread thread, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!BLOCKED_STATES.contains(thread.getState())) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "verifying thread did not block within " + timeout + "; was in state " + thread.getState());
            }
            Thread.onSpinWait();
        }
    }
}
