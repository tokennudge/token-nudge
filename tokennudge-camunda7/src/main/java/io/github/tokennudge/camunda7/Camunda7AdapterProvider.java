package io.github.tokennudge.camunda7;

import io.github.tokennudge.spi.EngineAdapter;
import io.github.tokennudge.spi.EngineAdapterProvider;
import io.github.tokennudge.spi.EngineConfig;

/**
 * Registers {@link Camunda7EngineAdapter} as the {@link EngineAdapterProvider} found by
 * {@code TokenNudge.forEngine(url)} via {@link java.util.ServiceLoader}. Registered in
 * {@code META-INF/services/io.github.tokennudge.spi.EngineAdapterProvider}.
 */
public final class Camunda7AdapterProvider implements EngineAdapterProvider {

    /**
     * Creates a new {@link Camunda7EngineAdapter} for the given configuration.
     *
     * @param config the engine configuration, never {@code null}
     * @return a new, not-yet-connected adapter
     */
    @Override
    public EngineAdapter create(EngineConfig config) {
        return new Camunda7EngineAdapter(config);
    }
}
