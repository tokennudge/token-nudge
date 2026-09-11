package io.github.tokennudge.junit5;

import io.github.tokennudge.NudgeOperations;
import io.github.tokennudge.Simulation;
import io.github.tokennudge.SimulationId;
import io.github.tokennudge.TokenNudge;
import io.github.tokennudge.Verification;
import io.github.tokennudge.model.JournalEntry;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.lang.System.Logger.Level;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A JUnit 5 extension that owns the lifecycle of a single {@link TokenNudge} instance for a
 * test class: building and starting it lazily, resetting it between tests, checking the
 * journal for unwanted outcomes after each test, and stopping it when the class is done.
 *
 * <p>Typical usage, mirroring {@code tokennudge-brief.md}:
 * <pre>{@code
 * import static io.github.tokennudge.TokenNudge.*;
 *
 * class PaymentFlowTest {
 *     {@literal @}RegisterExtension
 *     static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://localhost:8080/engine-rest");
 *
 *     {@literal @}Test
 *     void payment() {
 *         nudge.simulate(externalTask("charge-card").willComplete(withVariables(Map.of("paid", true))));
 *         // ... run the black-box test ...
 *         nudge.verify(externalTask("charge-card").completed().times(1));
 *     }
 * }
 * }</pre>
 *
 * <h2>Static vs. instance field</h2>
 * <p>Whether {@link #beforeAll(ExtensionContext)} is ever invoked depends entirely on how the
 * field is declared, per the JUnit 5 {@code @RegisterExtension} contract:
 * <ul>
 *   <li><strong>Static field</strong> (the common case): {@code beforeAll} is invoked once
 *       for the class, so this extension switches into "static mode". The underlying
 *       {@link TokenNudge} is built and {@linkplain TokenNudge#start() started} lazily, on the
 *       first {@link #beforeEach(ExtensionContext)} call (not in {@code beforeAll} itself), so
 *       a {@link #forEngine(Supplier)} URL supplier can resolve a value that only becomes
 *       available once Spring, Testcontainers, or similar has finished starting (for example
 *       inside a {@code @BeforeAll} method, which runs before this extension's
 *       {@code beforeEach}). It is shared by every test in the class, {@link #reset()} between
 *       each one, and stopped in {@link #afterAll(ExtensionContext)}.</li>
 *   <li><strong>Instance field</strong>: {@code beforeAll}/{@code afterAll} are never invoked
 *       (JUnit does not construct a test instance, and therefore cannot read an instance
 *       field, before entering a container), so this extension stays in the default
 *       "instance mode": a fresh instance is built and started in {@link #beforeEach}, and
 *       stopped again in {@link #afterEach}, for every single test.</li>
 * </ul>
 *
 * <h2>{@code @Nested} classes</h2>
 * <p>A static field declared on an outer class is inherited by {@code @Nested} classes, and
 * JUnit invokes {@code beforeAll}/{@code afterAll} again for the nested class's own container.
 * This extension only starts once (the first {@code beforeEach} across the whole class
 * hierarchy) and only stops from the <em>declaring</em> class's {@code afterAll} &mdash; the
 * one whose {@link ExtensionContext#getRequiredTestClass()} matches the class in which
 * {@code beforeAll} was first observed &mdash; so a {@code @Nested} class's own
 * {@code afterAll} never stops or rebuilds the outer instance early.
 *
 * <h2>Start failures</h2>
 * <p>If {@link TokenNudge.Builder#start()} fails (for example the engine is unreachable), the
 * failure is cached: {@link #beforeEach} fails every subsequent test in the class with the
 * same underlying message, instead of retrying the (likely still broken) engine for every
 * test method.
 *
 * <h2>{@code afterEach} ordering</h2>
 * <p>The journal check (action errors / unmatched wait states) runs first; {@link #reset()}
 * (static mode) or {@link TokenNudge#stop()} (instance mode) always runs afterwards, in a
 * {@code finally} block, so the next test starts clean even when the check itself fails. If a
 * test method itself already failed, JUnit reports that failure as the primary one and adds
 * this extension's journal-check failure (if any) as a suppressed exception, not the other way
 * around.
 *
 * <h2>Parallel execution</h2>
 * <p>This extension is <strong>not</strong> designed for JUnit 5 parallel execution of
 * multiple test methods within the same class when used as a static field: {@link #reset()}
 * in one test's {@code afterEach} races with {@link #simulate(Simulation)}/{@link #verify}
 * calls made by another, concurrently running test in the same class. If parallel execution
 * is enabled globally, either keep affected test classes on
 * {@code @Execution(ExecutionMode.SAME_THREAD)}, or give them a shared
 * {@code @ResourceLock} keyed by the test class, so their test methods never run
 * concurrently against each other.
 */
public final class TokenNudgeExtension
        implements NudgeOperations, BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback,
        ParameterResolver {

    private static final System.Logger LOGGER = System.getLogger(TokenNudgeExtension.class.getName());

    private final Supplier<String> engineRestUrl;
    private final Object startLock = new Object();
    private final AtomicReference<Class<?>> owningTestClass = new AtomicReference<>();

    private volatile Duration pollInterval;
    private volatile Duration verifyTimeout;
    private volatile boolean failOnActionErrors = true;
    private volatile boolean failOnUnmatched = false;
    private volatile Consumer<TokenNudge.Builder> customizer;
    private volatile boolean started = false;
    private volatile boolean staticMode = false;
    private volatile TokenNudge nudge;
    private volatile RuntimeException startFailure;

    private TokenNudgeExtension(Supplier<String> engineRestUrl) {
        this.engineRestUrl = engineRestUrl;
    }

    /**
     * Creates an extension that connects to the given, already-known engine-rest base URL.
     *
     * @param engineRestUrl the engine-rest base URL, never {@code null}
     * @return a new, not-yet-started extension
     * @throws NullPointerException if {@code engineRestUrl} is {@code null}
     */
    public static TokenNudgeExtension forEngine(String engineRestUrl) {
        Objects.requireNonNull(engineRestUrl, "engineRestUrl must not be null");
        return forEngine(() -> engineRestUrl);
    }

    /**
     * Creates an extension that resolves its engine-rest base URL lazily, the first time it
     * is actually needed (the first {@link #beforeEach(ExtensionContext)} call). This lets the
     * supplier read a value that is only known once a container (Testcontainers) or an
     * embedded server (a random port) has already started, typically inside a
     * {@code @BeforeAll} method.
     *
     * @param engineRestUrl supplies the engine-rest base URL on demand, never {@code null};
     *                      must not itself return {@code null}
     * @return a new, not-yet-started extension
     * @throws NullPointerException if {@code engineRestUrl} is {@code null}
     */
    public static TokenNudgeExtension forEngine(Supplier<String> engineRestUrl) {
        Objects.requireNonNull(engineRestUrl, "engineRestUrl must not be null");
        return new TokenNudgeExtension(engineRestUrl);
    }

    /**
     * Sets the loop's poll interval. See {@link TokenNudge.Builder#pollInterval(Duration)}.
     *
     * @param pollInterval the poll interval, never {@code null}
     * @return this extension
     * @throws IllegalStateException if called after this extension has started
     */
    public TokenNudgeExtension pollInterval(Duration pollInterval) {
        checkNotStarted();
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        return this;
    }

    /**
     * Sets the default verify timeout. See {@link TokenNudge.Builder#verifyTimeout(Duration)}.
     *
     * @param verifyTimeout the default verify timeout, never {@code null}
     * @return this extension
     * @throws IllegalStateException if called after this extension has started
     */
    public TokenNudgeExtension verifyTimeout(Duration verifyTimeout) {
        checkNotStarted();
        this.verifyTimeout = Objects.requireNonNull(verifyTimeout, "verifyTimeout must not be null");
        return this;
    }

    /**
     * Sets whether {@link #actionErrors()} being non-empty after a test fails that test.
     * Default: {@code true}.
     *
     * @param failOnActionErrors whether to fail on action errors
     * @return this extension
     * @throws IllegalStateException if called after this extension has started
     */
    public TokenNudgeExtension failOnActionErrors(boolean failOnActionErrors) {
        checkNotStarted();
        this.failOnActionErrors = failOnActionErrors;
        return this;
    }

    /**
     * Sets whether {@link #unmatched()} being non-empty after a test fails that test.
     * Default: {@code false}.
     *
     * @param failOnUnmatched whether to fail on unmatched wait states
     * @return this extension
     * @throws IllegalStateException if called after this extension has started
     */
    public TokenNudgeExtension failOnUnmatched(boolean failOnUnmatched) {
        checkNotStarted();
        this.failOnUnmatched = failOnUnmatched;
        return this;
    }

    /**
     * Registers a customizer applied to the {@link TokenNudge.Builder} right before
     * {@link TokenNudge.Builder#start()}, after {@link #pollInterval(Duration)}/
     * {@link #verifyTimeout(Duration)} (if set) have already been applied &mdash; so the
     * customizer can still override them, and can set anything else the builder supports (for
     * example {@code adapterProvider(...)}, most commonly used by this module's own tests to
     * inject a fake adapter).
     *
     * @param customizer the customizer to apply, never {@code null}
     * @return this extension
     * @throws NullPointerException  if {@code customizer} is {@code null}
     * @throws IllegalStateException if called after this extension has started
     */
    public TokenNudgeExtension configure(Consumer<TokenNudge.Builder> customizer) {
        checkNotStarted();
        this.customizer = Objects.requireNonNull(customizer, "customizer must not be null");
        return this;
    }

    /**
     * Returns the managed {@link TokenNudge} instance.
     *
     * @return the started instance
     * @throws IllegalStateException if this extension has not started yet (for example called
     *                                from a field initializer, a constructor, or before the
     *                                first {@code beforeEach}), or if starting it previously
     *                                failed
     */
    public TokenNudge get() {
        RuntimeException failure = startFailure;
        if (failure != null) {
            throw new IllegalStateException("TokenNudgeExtension failed to start: " + failure.getMessage(), failure);
        }
        TokenNudge instance = nudge;
        if (instance == null) {
            throw new IllegalStateException(
                    "TokenNudgeExtension has not started yet. It is only available once beforeEach has run, "
                            + "for example from a @Test, @BeforeEach, or @AfterEach method body or parameter - "
                            + "not from a field initializer, a static/instance initializer, or the test class "
                            + "constructor.");
        }
        return instance;
    }

    private void checkNotStarted() {
        if (started) {
            throw new IllegalStateException("TokenNudgeExtension configuration is frozen once it has started");
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        staticMode = true;
        owningTestClass.compareAndSet(null, context.getRequiredTestClass());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (!context.getRequiredTestClass().equals(owningTestClass.get())) {
            // A @Nested class inherited this static field; its own afterAll must not stop or
            // rebuild the outer, owning class's instance.
            return;
        }
        synchronized (startLock) {
            stopQuietly();
            // Release ownership and drop the stopped instance (and any cached start failure), so
            // that a static field shared through inheritance - an abstract base test class whose
            // sibling subclasses each run their own suite - starts a fresh TokenNudge for the next
            // owning class instead of silently reusing this stopped one.
            nudge = null;
            startFailure = null;
            owningTestClass.set(null);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        if (staticMode) {
            ensureStartedOnce();
        } else {
            startNow();
        }
        RuntimeException failure = startFailure;
        if (failure != null) {
            throw new IllegalStateException("TokenNudgeExtension failed to start: " + failure.getMessage(), failure);
        }
    }

    private void ensureStartedOnce() {
        synchronized (startLock) {
            if (nudge == null && startFailure == null) {
                startNow();
            }
        }
    }

    private void startNow() {
        started = true;
        try {
            TokenNudge.Builder builder = TokenNudge.forEngine(resolveEngineRestUrl());
            if (pollInterval != null) {
                builder.pollInterval(pollInterval);
            }
            if (verifyTimeout != null) {
                builder.verifyTimeout(verifyTimeout);
            }
            Consumer<TokenNudge.Builder> configuredCustomizer = customizer;
            if (configuredCustomizer != null) {
                configuredCustomizer.accept(builder);
            }
            nudge = builder.start();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                    "TokenNudgeExtension failed to start; every test using it will fail with this cause until "
                            + "the class is reset (a fresh test run)",
                    e);
            startFailure = e;
        }
    }

    private String resolveEngineRestUrl() {
        String url = engineRestUrl.get();
        return Objects.requireNonNull(url, "the engineRestUrl supplier returned null");
    }

    @Override
    public void afterEach(ExtensionContext context) {
        try {
            checkJournal();
        } finally {
            if (staticMode) {
                TokenNudge instance = nudge;
                if (instance != null) {
                    instance.reset();
                }
            } else {
                stopQuietly();
            }
        }
    }

    private void checkJournal() {
        TokenNudge instance = nudge;
        if (instance == null) {
            // Starting itself already failed (and beforeEach already reported that failure);
            // nothing meaningful to check here.
            return;
        }
        StringBuilder message = new StringBuilder();
        if (failOnActionErrors) {
            appendSection(message, "action error(s)", instance.actionErrors());
        }
        if (failOnUnmatched) {
            appendSection(message, "unmatched wait state(s)", instance.unmatched());
        }
        if (!message.isEmpty()) {
            throw new AssertionError(message.toString());
        }
    }

    private static void appendSection(StringBuilder message, String label, List<JournalEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        message.append(entries.size()).append(' ').append(label).append(":\n");
        for (JournalEntry entry : entries) {
            message.append("  - ").append(describe(entry)).append('\n');
        }
    }

    private static String describe(JournalEntry entry) {
        return entry.waitState().kind() + " '" + entry.waitState().name() + "' (id=" + entry.waitState().id()
                + ", process=" + entry.waitState().processInstanceId() + "): " + entry.outcome()
                + entry.error().map(e -> " - " + e).orElse("");
    }

    private void stopQuietly() {
        TokenNudge instance = nudge;
        if (instance != null) {
            instance.stop();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == TokenNudge.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        if (parameterContext.getDeclaringExecutable() instanceof Constructor<?>) {
            throw new ParameterResolutionException(
                    "TokenNudge cannot be injected into a test class constructor: it has not started yet at "
                            + "that point. Inject it into a @Test, @BeforeEach, or @AfterEach method parameter "
                            + "instead.");
        }
        try {
            return get();
        } catch (IllegalStateException e) {
            throw new ParameterResolutionException(e.getMessage(), e);
        }
    }

    @Override
    public SimulationId simulate(Simulation simulation) {
        return get().simulate(simulation);
    }

    @Override
    public boolean removeSimulation(SimulationId id) {
        return get().removeSimulation(id);
    }

    @Override
    public List<Simulation> simulations() {
        return get().simulations();
    }

    @Override
    public void verify(Verification verification) {
        get().verify(verification);
    }

    @Override
    public List<JournalEntry> journal() {
        return get().journal();
    }

    @Override
    public List<JournalEntry> actionErrors() {
        return get().actionErrors();
    }

    @Override
    public List<JournalEntry> unmatched() {
        return get().unmatched();
    }

    @Override
    public void reset() {
        get().reset();
    }

    @Override
    public void resetJournal() {
        get().resetJournal();
    }
}
