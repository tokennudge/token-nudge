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
 * {@code TokenNudge} but are not public API; see {@code docs/PLAN.md} section 2.1 for why
 * they live here rather than under {@code io.github.tokennudge.internal}.
 */
package io.github.tokennudge;
