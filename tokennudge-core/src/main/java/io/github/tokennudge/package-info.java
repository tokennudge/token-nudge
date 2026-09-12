/**
 * Public API of TokenNudge: the {@code TokenNudge} facade, the fluent DSL for declaring
 * simulations and verifications, and the small set of immutable value types
 * ({@code Variables}, {@code SimulationId}, {@code Simulation}, {@code Verification}, ...)
 * used to describe them.
 *
 * <p>Everything in this package is engine-agnostic; transport to a specific engine (for
 * example Camunda 7 / CIB Seven) is provided by separate adapter modules discovered
 * through {@code io.github.tokennudge.spi}.
 *
 * <p>This package also holds a few package-private classes ({@code VerificationEvaluator},
 * {@code IterationClock}, {@code LoopIterationClock}) that support {@code Verification} and
 * {@code TokenNudge} but are not public API. They live here rather than under
 * {@code io.github.tokennudge.internal} so that the inspection methods they need on
 * {@code Verification} can stay package-private.
 */
package io.github.tokennudge;
