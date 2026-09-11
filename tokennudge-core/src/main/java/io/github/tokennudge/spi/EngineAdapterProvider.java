package io.github.tokennudge.spi;

/**
 * Factory for an {@link EngineAdapter}, discovered via {@link java.util.ServiceLoader}.
 *
 * <p>Adapter modules (for example {@code tokennudge-camunda7}) implement this interface and
 * register the implementation in
 * {@code META-INF/services/io.github.tokennudge.spi.EngineAdapterProvider}, so that
 * {@code TokenNudge.forEngine(url)} can locate them without a compile-time dependency.
 */
public interface EngineAdapterProvider {

    /**
     * Creates a new adapter for the given configuration.
     *
     * @param config the engine configuration, never {@code null}
     * @return a new, not-yet-connected adapter; never {@code null}
     */
    EngineAdapter create(EngineConfig config);
}
