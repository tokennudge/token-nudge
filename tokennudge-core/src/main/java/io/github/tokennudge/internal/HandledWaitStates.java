package io.github.tokennudge.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe, bounded record of wait-state ids that {@code NudgeLoop} has already claimed
 * and acted on (successfully, with an action failure, or with a lost claim), so that they
 * are never processed again ("no automatic retry").
 *
 * <p>Bounded to a fixed capacity (~10k ids by default): once full, the oldest-added id is
 * evicted to make room for a new one. A real wait state is normally removed from engine
 * discovery entirely once the token has moved past it, so eviction under real usage should
 * be rare; this bound simply guards against unbounded memory growth over a very long-lived
 * {@code TokenNudge} instance.
 *
 * <p>Not public API; see the package documentation.
 */
public final class HandledWaitStates {

    /** Default capacity, matching the plan's "bounded LRU (~10k ids)" sizing. */
    public static final int DEFAULT_MAX_SIZE = 10_000;

    private final int maxSize;
    private final Map<String, Boolean> ids;

    /**
     * Creates a new instance with the {@linkplain #DEFAULT_MAX_SIZE default capacity}.
     */
    public HandledWaitStates() {
        this(DEFAULT_MAX_SIZE);
    }

    /**
     * Creates a new instance with the given capacity.
     *
     * @param maxSize the maximum number of ids to remember; must be positive
     * @throws IllegalArgumentException if {@code maxSize} is not positive
     */
    public HandledWaitStates(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
        this.ids = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > HandledWaitStates.this.maxSize;
            }
        };
    }

    /**
     * Returns whether the given wait-state id has already been handled.
     *
     * @param waitStateId the wait-state id, never {@code null}
     * @return {@code true} if already handled
     */
    public synchronized boolean contains(String waitStateId) {
        return ids.containsKey(waitStateId);
    }

    /**
     * Marks a wait-state id as handled.
     *
     * @param waitStateId the wait-state id, never {@code null}
     */
    public synchronized void add(String waitStateId) {
        ids.put(waitStateId, Boolean.TRUE);
    }

    /**
     * Forgets every handled id.
     */
    public synchronized void clear() {
        ids.clear();
    }

    /**
     * Returns the number of ids currently remembered.
     *
     * @return the current size
     */
    public synchronized int size() {
        return ids.size();
    }
}
