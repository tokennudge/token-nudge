package io.github.tokennudge.examples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A small Spring Boot service embedding a Camunda 7 engine: the README quickstart demo for
 * TokenNudge.
 *
 * <p>The {@code payment} process performs a risk check and a card charge, both external
 * tasks, so that a black-box test driving only this app's HTTP API needs something external
 * to play the role of those workers - exactly what TokenNudge is for. This module never
 * depends on TokenNudge from main code; it is a test-only dependency of the tests under
 * {@code src/test/java}.
 */
@SpringBootApplication
public class TokenNudgeExamplesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TokenNudgeExamplesApplication.class, args);
    }
}
