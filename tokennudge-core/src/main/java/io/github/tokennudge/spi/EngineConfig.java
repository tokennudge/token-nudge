package io.github.tokennudge.spi;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration passed to an {@link EngineAdapterProvider} when creating an
 * {@link EngineAdapter}.
 *
 * @param baseUri          the engine-rest base URI, never {@code null}
 * @param requestTimeout   the per-request timeout, never {@code null} or negative
 * @param lockDuration     how long a claimed external task should remain locked, never
 *                         {@code null} or negative
 * @param workerId         the worker id to identify claims/completions with, never
 *                         {@code null}
 * @param maxResultsPerPoll the maximum number of wait states to request per discovery
 *                         call; must be positive
 * @param headers          extra HTTP headers (for example authentication) to send with
 *                         every request, never {@code null}
 */
public record EngineConfig(
        URI baseUri,
        Duration requestTimeout,
        Duration lockDuration,
        String workerId,
        int maxResultsPerPoll,
        Map<String, String> headers) {

    /**
     * Validates required fields and makes a defensive, unmodifiable copy of
     * {@code headers}.
     *
     * @throws NullPointerException     if {@code baseUri}, {@code requestTimeout},
     *                                  {@code lockDuration}, {@code workerId}, or
     *                                  {@code headers} is {@code null}
     * @throws IllegalArgumentException if {@code requestTimeout} or {@code lockDuration} is
     *                                  negative, or {@code maxResultsPerPoll} is not
     *                                  positive
     */
    public EngineConfig {
        Objects.requireNonNull(baseUri, "baseUri must not be null");
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        Objects.requireNonNull(lockDuration, "lockDuration must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        if (requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must not be negative: " + requestTimeout);
        }
        if (lockDuration.isNegative()) {
            throw new IllegalArgumentException("lockDuration must not be negative: " + lockDuration);
        }
        if (maxResultsPerPoll <= 0) {
            throw new IllegalArgumentException("maxResultsPerPoll must be positive: " + maxResultsPerPoll);
        }
        headers = Map.copyOf(headers);
    }
}
