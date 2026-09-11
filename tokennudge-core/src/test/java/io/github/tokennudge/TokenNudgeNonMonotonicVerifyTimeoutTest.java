package io.github.tokennudge;

import io.github.tokennudge.testsupport.FakeEngineAdapter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.tokennudge.TokenNudge.externalTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for the iteration-4 re-review's carried-over SHOULD-FIX: a non-monotonic
 * verification ({@code never()}/{@code atMost()}) must fail explicitly, rather than
 * silently evaluating a stale journal snapshot, when the loop's in-flight iteration holds
 * {@code iterationLock} for longer than the whole verify timeout &mdash; the realistic case
 * being a slow adapter call with {@code verifyTimeout < requestTimeout}.
 *
 * <p>Synchronization is purely via a {@link CountDownLatch} ({@link FakeEngineAdapter}'s
 * first {@code discover()} call is pinned until the test releases it): no fixed sleep is
 * used to coordinate the test's steps. The short {@code verifyTimeout} passed to
 * {@code verify(...)} is itself the behavior under test, not a synchronization sleep.
 */
class TokenNudgeNonMonotonicVerifyTimeoutTest {

    private static final String TOPIC = "charge-card";

    @Test
    void neverFailsExplicitlyInsteadOfFallingBackToAStaleJournalWhenTheLoopIsStuckPastTheVerifyTimeout()
            throws Exception {
        FakeEngineAdapter adapter = new FakeEngineAdapter();
        CountDownLatch discoveryEntered = new CountDownLatch(1);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        adapter.blockNextDiscovery(discoveryEntered, releaseDiscovery);

        TokenNudge nudge = TokenNudge.forAdapter(adapter)
                .pollInterval(Duration.ofMillis(20))
                // Deliberately much shorter than how long the first iteration will be
                // blocked inside discover() (until the test releases it below).
                .verifyTimeout(Duration.ofMillis(100))
                .build();
        nudge.simulate(externalTask(TOPIC).willComplete());
        nudge.start();

        try {
            // The loop's first iteration is now blocked inside discover(), still holding
            // iterationLock: exactly the scenario that starves freshIterationBaseline().
            assertThat(discoveryEntered.await(5, TimeUnit.SECONDS))
                    .as("the loop's first iteration should have entered discover() by now")
                    .isTrue();

            assertThatThrownBy(() -> nudge.verify(externalTask(TOPIC).completed().never()))
                    .as("verify() must fail explicitly instead of evaluating a stale, "
                            + "pre-in-flight-iteration journal snapshot")
                    .isInstanceOf(VerificationException.class)
                    .hasMessageContaining("timed out waiting for a fresh loop iteration");
        } finally {
            releaseDiscovery.countDown();
            nudge.stop();
        }
    }
}
