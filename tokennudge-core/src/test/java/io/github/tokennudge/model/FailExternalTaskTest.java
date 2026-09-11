package io.github.tokennudge.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class FailExternalTaskTest {

    @Test
    void exposesGivenFields() {
        var action = new FailExternalTask("boom", 3, Duration.ofSeconds(30));

        assertThat(action.errorMessage()).isEqualTo("boom");
        assertThat(action.retries()).isEqualTo(3);
        assertThat(action.retryTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void zeroRetriesIsAllowedAndCreatesAnIncident() {
        var action = new FailExternalTask("boom", 0, Duration.ZERO);
        assertThat(action.retries()).isZero();
    }

    @Test
    void negativeRetriesAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FailExternalTask("boom", -1, Duration.ofSeconds(1)));
    }

    @Test
    void negativeRetryTimeoutIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FailExternalTask("boom", 1, Duration.ofSeconds(-1)));
    }

    @Test
    void nullErrorMessageIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new FailExternalTask(null, 1, Duration.ofSeconds(1)));
    }

    @Test
    void nullRetryTimeoutIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new FailExternalTask("boom", 1, null));
    }
}
