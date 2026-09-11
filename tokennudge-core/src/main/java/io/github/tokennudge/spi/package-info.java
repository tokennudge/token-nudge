/**
 * The engine port: the {@link io.github.tokennudge.spi.EngineAdapter} interface a transport
 * module (for example {@code tokennudge-camunda7}) implements, and the small set of types
 * used to configure and discover through it.
 *
 * <p>{@code TokenNudge.forEngine(url)} locates an {@link io.github.tokennudge.spi.EngineAdapterProvider}
 * via {@link java.util.ServiceLoader}; adapter modules register their provider in
 * {@code META-INF/services/io.github.tokennudge.spi.EngineAdapterProvider}.
 */
package io.github.tokennudge.spi;
