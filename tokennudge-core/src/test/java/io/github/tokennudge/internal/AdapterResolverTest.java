package io.github.tokennudge.internal;

import io.github.tokennudge.spi.EngineAdapter;
import io.github.tokennudge.spi.EngineAdapterProvider;
import io.github.tokennudge.spi.EngineConfig;
import io.github.tokennudge.testsupport.FakeEngineAdapter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests {@link AdapterResolver}'s zero/one/many-provider validation.
 *
 * <p>The zero-provider case against the real {@link java.util.ServiceLoader} is covered
 * directly ({@link #resolveViaRealServiceLoaderFindsZeroProvidersOnTheCoreTestClasspath()}):
 * {@code tokennudge-core}'s test classpath has no {@code EngineAdapterProvider} registered.
 * The one/many-provider cases use the injectable
 * {@link AdapterResolver#resolve(EngineConfig, List)} overload instead of adding test
 * {@code META-INF/services} entries, so no test provider ever leaks into other tests (for
 * example ones resolving via {@code TokenNudge.forEngine(...)}).
 */
class AdapterResolverTest {

    private static final EngineConfig CONFIG = new EngineConfig(
            URI.create("http://localhost:8080/engine-rest"),
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            "worker-1",
            50,
            Map.of());

    @Test
    void resolveViaRealServiceLoaderFindsZeroProvidersOnTheCoreTestClasspath() {
        assertThatIllegalStateException()
                .isThrownBy(() -> AdapterResolver.resolve(CONFIG))
                .withMessageContaining("tokennudge-camunda7");
    }

    @Test
    void resolveRejectsNullConfig() {
        assertThatNullPointerException().isThrownBy(() -> AdapterResolver.resolve(null, List.of()));
    }

    @Test
    void resolveRejectsNullProviderList() {
        assertThatNullPointerException().isThrownBy(() -> AdapterResolver.resolve(CONFIG, null));
    }

    @Test
    void resolveThrowsWithZeroProvidersMentioningTheCamunda7Module() {
        assertThatIllegalStateException()
                .isThrownBy(() -> AdapterResolver.resolve(CONFIG, List.of()))
                .withMessageContaining("tokennudge-camunda7")
                .withMessageContaining("forAdapter");
    }

    @Test
    void resolveThrowsWithMultipleProvidersNamingEachOne() {
        EngineAdapterProvider first = config -> new FakeEngineAdapter();
        EngineAdapterProvider second = config -> new FakeEngineAdapter();

        assertThatIllegalStateException()
                .isThrownBy(() -> AdapterResolver.resolve(CONFIG, List.of(first, second)))
                .withMessageContaining(first.getClass().getName())
                .withMessageContaining(second.getClass().getName());
    }

    @Test
    void resolveUsesTheSingleProviderAndPassesItTheConfig() {
        FakeEngineAdapter fake = new FakeEngineAdapter();
        EngineConfig[] receivedConfig = new EngineConfig[1];
        EngineAdapterProvider provider = config -> {
            receivedConfig[0] = config;
            return fake;
        };

        EngineAdapter resolved = AdapterResolver.resolve(CONFIG, List.of(provider));

        assertThat(resolved).isSameAs(fake);
        assertThat(receivedConfig[0]).isSameAs(CONFIG);
    }
}
