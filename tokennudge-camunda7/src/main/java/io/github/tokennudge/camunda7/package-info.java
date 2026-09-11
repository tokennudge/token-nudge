/**
 * Camunda 7 / CIB Seven engine-rest transport adapter for TokenNudge.
 *
 * <p>As of this iteration, this package provides the HTTP transport layer only:
 * {@link io.github.tokennudge.camunda7.EngineRestClient} (a thin, package-private
 * {@code java.net.http.HttpClient} wrapper implementing the
 * {@code io.github.tokennudge.spi.EngineAdapter} failure contract),
 * {@link io.github.tokennudge.camunda7.VariableCodec} (Java values &harr; engine-rest typed
 * values), and the {@link io.github.tokennudge.camunda7.dto} records they use. The
 * {@code EngineAdapter} implementation and its {@code EngineAdapterProvider}, which turn
 * these building blocks into a working TokenNudge engine port, land in a later iteration.
 */
package io.github.tokennudge.camunda7;
