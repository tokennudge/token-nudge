/**
 * Engine-agnostic model shared between the TokenNudge public API and the
 * {@code io.github.tokennudge.spi} engine port: wait states, selectors and their variable
 * matchers, completion/failure actions, and journal entries.
 *
 * <p>Types in this package are immutable value types (records and sealed interfaces) with
 * no dependency on a specific transport or engine implementation. They are considered
 * public API because engine adapters (for example {@code tokennudge-camunda7}) depend on
 * them directly.
 */
package io.github.tokennudge.model;
