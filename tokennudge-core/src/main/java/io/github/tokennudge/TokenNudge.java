package io.github.tokennudge;

import io.github.tokennudge.internal.AdapterResolver;
import io.github.tokennudge.internal.InMemoryJournal;
import io.github.tokennudge.internal.NudgeLoop;
import io.github.tokennudge.internal.SimulationRegistry;
import io.github.tokennudge.model.JournalEntry;
import io.github.tokennudge.model.Outcome;
import io.github.tokennudge.spi.EngineAdapter;
import io.github.tokennudge.spi.EngineAdapterProvider;
import io.github.tokennudge.spi.EngineConfig;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Entry point for the TokenNudge static DSL, and the facade that owns a simulation
 * registry, a journal, and the loop that discovers, matches, claims, and acts on wait
 * states against a real (or fake, for tests) BPMN engine.
 *
 * <p>Typical usage is via a static import, {@code import static io.github.tokennudge.TokenNudge.*;}, followed by:
 * <pre>{@code
 * TokenNudge nudge = TokenNudge.forEngine("http://localhost:8080/engine-rest")
 *         .pollInterval(Duration.ofMillis(250))
 *         .start();
 *
 * nudge.simulate(externalTask("charge-card").inProcess("payment").willComplete(withVariables(Map.of("paid", true))));
 * // ... run the black-box test ...
 * nudge.verify(externalTask("charge-card").completed().times(1));
 * nudge.reset();
 * }</pre>
 *
 * <p>Lifecycle: {@code NEW} &rarr; {@code RUNNING} &rarr; {@code STOPPED}. {@link #start()}
 * and {@link #stop()} are idempotent; once stopped, an instance cannot be restarted. All
 * {@link NudgeOperations} methods are safe to call from multiple threads.
 */
public final class TokenNudge implements NudgeOperations, AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(TokenNudge.class.getName());

    private enum State {
        NEW,
        RUNNING,
        STOPPED
    }

    private final EngineAdapter adapter;
    private final SimulationRegistry registry = new SimulationRegistry();
    private final InMemoryJournal journal = new InMemoryJournal();
    private final ReentrantLock iterationLock = new ReentrantLock();
    private final NudgeLoop loop;
    private final VerificationEvaluator evaluator;
    private final Duration verifyTimeout;
    private final Duration requestTimeout;

    private State state = State.NEW;

    private TokenNudge(
            EngineAdapter adapter,
            Duration pollInterval,
            Duration verifyTimeout,
            Duration requestTimeout,
            boolean captureVariables,
            int maxResultsPerPoll) {
        this.adapter = adapter;
        this.verifyTimeout = verifyTimeout;
        this.requestTimeout = requestTimeout;
        this.loop = new NudgeLoop(adapter, registry, journal, iterationLock, pollInterval, captureVariables, maxResultsPerPoll);
        this.evaluator = new VerificationEvaluator(journal, new LoopIterationClock(loop));
    }

    /**
     * Starts building a {@link TokenNudge} that talks to a real engine over {@code
     * engine-rest}, resolving the transport adapter via {@link java.util.ServiceLoader}
     * (see {@code Builder#adapterProvider} to bypass this).
     *
     * @param engineRestUrl the engine-rest base URL, never {@code null}
     * @return a new builder
     * @throws NullPointerException if {@code engineRestUrl} is {@code null}
     */
    public static Builder forEngine(String engineRestUrl) {
        Objects.requireNonNull(engineRestUrl, "engineRestUrl must not be null");
        Builder builder = new Builder();
        builder.baseUri = URI.create(engineRestUrl);
        return builder;
    }

    /**
     * Starts building a {@link TokenNudge} that uses the given adapter directly, bypassing
     * {@link java.util.ServiceLoader} resolution entirely. Intended for tests (with a
     * programmable fake adapter) and custom transports.
     *
     * @param adapter the adapter to use, never {@code null}
     * @return a new builder
     * @throws NullPointerException if {@code adapter} is {@code null}
     */
    public static Builder forAdapter(EngineAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter must not be null");
        Builder builder = new Builder();
        builder.adapter = adapter;
        return builder;
    }

    /**
     * Starts building a selector/simulation/verification for an external task, identified
     * by its topic name.
     *
     * @param topicName the external task topic name, never {@code null}
     * @return a new spec matching any external task on this topic
     * @throws NullPointerException if {@code topicName} is {@code null}
     */
    public static ExternalTaskSpec externalTask(String topicName) {
        Objects.requireNonNull(topicName, "topicName must not be null");
        return new ExternalTaskSpec(topicName);
    }

    /**
     * Wraps the given map as {@link Variables}, for use with a simulation's completion
     * method (for example {@link ExternalTaskSpec#willComplete(Variables)}).
     *
     * @param variables the source map; see {@link Variables#of(Map)} for supported value
     *                  types and copying semantics
     * @return the wrapped variables
     * @throws NullPointerException     if {@code variables} is {@code null}
     * @throws IllegalArgumentException if a key is {@code null}, or a value is of an
     *                                  unsupported type
     */
    public static Variables withVariables(Map<String, ?> variables) {
        return Variables.of(variables);
    }

    /**
     * Returns an empty set of variables, for use with a simulation's completion method
     * when no output variables should be submitted.
     *
     * @return {@link Variables#empty()}
     */
    public static Variables noVariables() {
        return Variables.empty();
    }

    /**
     * Starts the loop, first verifying the engine is reachable. Idempotent: calling this
     * again while already running, or after {@link #stop()}, does nothing and returns this
     * instance unchanged (a stopped instance cannot be restarted).
     *
     * @return this instance
     * @throws io.github.tokennudge.spi.EngineAccessException if the engine cannot be
     *                                                          reached
     */
    public synchronized TokenNudge start() {
        if (state != State.NEW) {
            return this;
        }
        adapter.checkConnectivity();
        loop.start();
        state = State.RUNNING;
        return this;
    }

    /**
     * Stops the loop (joining its thread for up to {@code requestTimeout + 1s}) and closes
     * the adapter. Idempotent: calling this again, or before {@link #start()}, does
     * nothing beyond ensuring the adapter is closed exactly once.
     */
    public synchronized void stop() {
        if (state == State.STOPPED) {
            return;
        }
        if (state == State.RUNNING) {
            loop.stop(requestTimeout.plusSeconds(1));
        }
        state = State.STOPPED;
        try {
            adapter.close();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to close the engine adapter", e);
        }
    }

    /**
     * Equivalent to {@link #stop()}, for use in try-with-resources.
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * Returns whether this instance is currently running (between {@link #start()} and
     * {@link #stop()}).
     *
     * @return {@code true} if running
     */
    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    @Override
    public SimulationId simulate(Simulation simulation) {
        Objects.requireNonNull(simulation, "simulation must not be null");
        registry.add(simulation);
        loop.wake();
        return simulation.id();
    }

    @Override
    public boolean removeSimulation(SimulationId id) {
        Objects.requireNonNull(id, "id must not be null");
        return registry.remove(id);
    }

    @Override
    public List<Simulation> simulations() {
        return registry.snapshot();
    }

    @Override
    public void verify(Verification verification) {
        Objects.requireNonNull(verification, "verification must not be null");
        loop.wake();
        evaluator.evaluate(verification, verifyTimeout);
    }

    @Override
    public List<JournalEntry> journal() {
        return journal.entries();
    }

    @Override
    public List<JournalEntry> actionErrors() {
        return journal.entries().stream().filter(entry -> entry.outcome() == Outcome.ACTION_FAILED).toList();
    }

    @Override
    public List<JournalEntry> unmatched() {
        return journal.entries().stream().filter(entry -> entry.outcome() == Outcome.UNMATCHED).toList();
    }

    @Override
    public void reset() {
        iterationLock.lock();
        try {
            registry.clear();
            journal.reset();
            loop.resetHandledWaitStates();
        } finally {
            iterationLock.unlock();
        }
    }

    @Override
    public void resetJournal() {
        iterationLock.lock();
        try {
            journal.reset();
        } finally {
            iterationLock.unlock();
        }
    }

    /**
     * Not thread-safe: build a single {@link TokenNudge} from a single thread, typically
     * in test setup.
     */
    public static final class Builder {

        private URI baseUri;
        private EngineAdapter adapter;
        private EngineAdapterProvider adapterProvider;
        private Duration pollInterval = Duration.ofMillis(250);
        private Duration verifyTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration lockDuration = Duration.ofSeconds(30);
        private String workerId = "tokennudge-" + UUID.randomUUID();
        private int maxResultsPerPoll = 50;
        private boolean captureVariables = true;
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets how long the loop waits for new work before polling again, when an
         * iteration did nothing. Default: 250ms.
         *
         * @param pollInterval the poll interval, never {@code null} or negative
         * @return this builder
         */
        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = requirePositiveOrZero(pollInterval, "pollInterval");
            return this;
        }

        /**
         * Sets the default timeout used by {@link #verify(Verification)} when a
         * verification did not set one via {@code within(...)}. Default: 5s.
         *
         * @param verifyTimeout the default verify timeout, never {@code null} or negative
         * @return this builder
         */
        public Builder verifyTimeout(Duration verifyTimeout) {
            this.verifyTimeout = requirePositiveOrZero(verifyTimeout, "verifyTimeout");
            return this;
        }

        /**
         * Sets the per-request timeout used by the resolved engine adapter, and, together
         * with an extra second, how long {@link #stop()} waits for the loop thread to
         * terminate. Default: 10s.
         *
         * @param requestTimeout the request timeout, never {@code null} or negative
         * @return this builder
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requirePositiveOrZero(requestTimeout, "requestTimeout");
            return this;
        }

        /**
         * Sets how long a claimed external task should remain locked. Default: 30s.
         *
         * @param lockDuration the lock duration, never {@code null} or negative
         * @return this builder
         */
        public Builder lockDuration(Duration lockDuration) {
            this.lockDuration = requirePositiveOrZero(lockDuration, "lockDuration");
            return this;
        }

        /**
         * Sets the worker id used to identify claims/completions. Default:
         * {@code "tokennudge-" + a random UUID}.
         *
         * @param workerId the worker id, never {@code null}
         * @return this builder
         */
        public Builder workerId(String workerId) {
            this.workerId = Objects.requireNonNull(workerId, "workerId must not be null");
            return this;
        }

        /**
         * Sets the maximum number of wait states requested per discovery call. Default:
         * 50.
         *
         * @param maxResultsPerPoll the maximum results per poll; must be positive
         * @return this builder
         * @throws IllegalArgumentException if {@code maxResultsPerPoll} is not positive
         */
        public Builder maxResultsPerPoll(int maxResultsPerPoll) {
            if (maxResultsPerPoll <= 0) {
                throw new IllegalArgumentException("maxResultsPerPoll must be positive: " + maxResultsPerPoll);
            }
            this.maxResultsPerPoll = maxResultsPerPoll;
            return this;
        }

        /**
         * Sets whether the loop always fetches process variables for a statically-matching
         * wait state, even if the matching simulation's selector does not itself require
         * them (so that they are captured in the journal). Default: {@code true}.
         *
         * @param captureVariables whether to always capture variables
         * @return this builder
         */
        public Builder captureVariables(boolean captureVariables) {
            this.captureVariables = captureVariables;
            return this;
        }

        /**
         * Adds an {@code Authorization: Basic ...} header computed from the given
         * credentials. Equivalent to calling {@link #header(String, String)} directly with
         * a pre-computed value.
         *
         * @param user     the username, never {@code null}
         * @param password the password, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code user} or {@code password} is {@code null}
         */
        public Builder basicAuth(String user, String password) {
            Objects.requireNonNull(user, "user must not be null");
            Objects.requireNonNull(password, "password must not be null");
            String credentials = user + ":" + password;
            String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            return header("Authorization", "Basic " + token);
        }

        /**
         * Adds an HTTP header to send with every request to the engine.
         *
         * @param name  the header name, never {@code null}
         * @param value the header value, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code name} or {@code value} is {@code null}
         */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(value, "value must not be null");
            headers.put(name, value);
            return this;
        }

        /**
         * Uses the given provider directly, bypassing {@link java.util.ServiceLoader}
         * resolution.
         *
         * @param adapterProvider the provider to use, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code adapterProvider} is {@code null}
         */
        public Builder adapterProvider(EngineAdapterProvider adapterProvider) {
            this.adapterProvider = Objects.requireNonNull(adapterProvider, "adapterProvider must not be null");
            return this;
        }

        /**
         * Builds a new, not-yet-started {@link TokenNudge}.
         *
         * @return the new instance
         * @throws IllegalStateException if resolving the adapter via
         *                                {@link java.util.ServiceLoader} finds zero or more
         *                                than one provider (only applies when using
         *                                {@link TokenNudge#forEngine(String)} without
         *                                {@link #adapterProvider(EngineAdapterProvider)})
         */
        public TokenNudge build() {
            EngineAdapter resolvedAdapter = adapter != null ? adapter : createAdapter();
            return new TokenNudge(
                    resolvedAdapter, pollInterval, verifyTimeout, requestTimeout, captureVariables, maxResultsPerPoll);
        }

        private EngineAdapter createAdapter() {
            EngineConfig config = new EngineConfig(
                    Objects.requireNonNull(
                            baseUri,
                            "no engine URL or adapter configured; use TokenNudge.forEngine(...) or "
                                    + "TokenNudge.forAdapter(...)"),
                    requestTimeout,
                    lockDuration,
                    workerId,
                    maxResultsPerPoll,
                    headers);
            return adapterProvider != null ? adapterProvider.create(config) : AdapterResolver.resolve(config);
        }

        /**
         * Builds and immediately {@linkplain TokenNudge#start() starts} a new
         * {@link TokenNudge}.
         *
         * @return the started instance
         */
        public TokenNudge start() {
            return build().start();
        }

        private static Duration requirePositiveOrZero(Duration duration, String name) {
            Objects.requireNonNull(duration, name + " must not be null");
            if (duration.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative: " + duration);
            }
            return duration;
        }
    }
}
