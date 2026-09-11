package io.github.tokennudge.internal;

import io.github.tokennudge.spi.EngineAdapter;
import io.github.tokennudge.spi.EngineAdapterProvider;
import io.github.tokennudge.spi.EngineConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Locates the single {@link EngineAdapterProvider} on the classpath via
 * {@link ServiceLoader}, for {@code TokenNudge.forEngine(url)}.
 *
 * <p>Fails fast if zero or more than one provider is found: {@code forAdapter(...)} and
 * {@code Builder#adapterProvider(...)} are the escape hatches for tests and custom
 * transports that would otherwise conflict with this resolver.
 *
 * <p>Not public API; see the package documentation.
 */
public final class AdapterResolver {

    private AdapterResolver() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Resolves the single registered {@link EngineAdapterProvider} via
     * {@link ServiceLoader#load(Class)} and uses it to create an adapter.
     *
     * @param config the configuration to create the adapter with, never {@code null}
     * @return the created adapter, never {@code null}
     * @throws NullPointerException  if {@code config} is {@code null}
     * @throws IllegalStateException if zero or more than one provider is found
     */
    public static EngineAdapter resolve(EngineConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        List<EngineAdapterProvider> providers = new ArrayList<>();
        for (EngineAdapterProvider provider : ServiceLoader.load(EngineAdapterProvider.class)) {
            providers.add(provider);
        }
        return resolve(config, providers);
    }

    /**
     * Creates an adapter from an explicitly supplied list of candidate providers, applying
     * the same zero/one/many validation as {@link #resolve(EngineConfig)}.
     *
     * <p>Exposed (rather than relying solely on {@link ServiceLoader} scanning the test
     * classpath) so that the zero-provider and multiple-provider failure cases can be unit
     * tested deterministically, independent of what is actually registered under
     * {@code META-INF/services} for the test run.
     *
     * @param config    the configuration to create the adapter with, never {@code null}
     * @param providers the candidate providers, never {@code null}
     * @return the created adapter, never {@code null}
     * @throws NullPointerException  if {@code config} or {@code providers} is {@code null}
     * @throws IllegalStateException if {@code providers} is empty or has more than one
     *                                element
     */
    public static EngineAdapter resolve(EngineConfig config, List<EngineAdapterProvider> providers) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(providers, "providers must not be null");

        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "No " + EngineAdapterProvider.class.getName() + " found on the classpath. "
                            + "Add an engine adapter module, e.g. tokennudge-camunda7, as a dependency, "
                            + "or use TokenNudge.forAdapter(...)/Builder#adapterProvider(...) instead.");
        }
        if (providers.size() > 1) {
            String found = providers.stream()
                    .map(provider -> provider.getClass().getName())
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException(
                    "Multiple " + EngineAdapterProvider.class.getName() + " implementations found on the "
                            + "classpath: [" + found + "]. Configure one explicitly via "
                            + "TokenNudge.Builder#adapterProvider(...).");
        }
        return providers.get(0).create(config);
    }
}
