package io.github.tokennudge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationExceptionTest {

    @Test
    void isAnAssertionError() {
        VerificationException exception = new VerificationException("expected at least 1, got 0");
        assertThat(exception).isInstanceOf(AssertionError.class);
        assertThat(exception.getMessage()).isEqualTo("expected at least 1, got 0");
    }
}
