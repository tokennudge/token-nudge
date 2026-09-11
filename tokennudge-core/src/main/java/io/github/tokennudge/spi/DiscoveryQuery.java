package io.github.tokennudge.spi;

import io.github.tokennudge.model.WaitStateKind;

import java.util.Objects;
import java.util.Set;

/**
 * A request to discover wait states of a given kind, limited to a set of names (topic
 * names, task definition keys, or message names, depending on {@code kind}).
 *
 * <p>Only kinds/names referenced by at least one registered simulation are ever
 * discovered; {@link EngineAdapter#discover(DiscoveryQuery)} implementations should not
 * return wait states outside {@code names}.
 *
 * @param kind      the kind of wait state to discover, never {@code null}
 * @param names     the distinct names to discover, never {@code null} or empty
 * @param maxResults the maximum number of results to return per name; must be positive
 */
public record DiscoveryQuery(WaitStateKind kind, Set<String> names, int maxResults) {

    /**
     * Validates required fields and makes a defensive, unmodifiable copy of {@code names}.
     *
     * @throws NullPointerException     if {@code kind} or {@code names} is {@code null}
     * @throws IllegalArgumentException if {@code names} is empty, or {@code maxResults} is
     *                                  not positive
     */
    public DiscoveryQuery {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(names, "names must not be null");
        names = Set.copyOf(names);
        if (names.isEmpty()) {
            throw new IllegalArgumentException("names must not be empty");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be positive: " + maxResults);
        }
    }
}
