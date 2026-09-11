/**
 * Test-support types deliberately shipped in {@code tokennudge-core}'s test-jar for reuse
 * by other modules' tests, unlike {@code io.github.tokennudge.internal}, which is not meant
 * to be depended on outside this module.
 *
 * <p>{@link io.github.tokennudge.testsupport.FakeEngineAdapter} is the main entry point:
 * an in-memory, programmable {@code EngineAdapter} used by {@code tokennudge-core}'s own
 * loop/facade tests, and reused by {@code tokennudge-junit5} (added in a later iteration)
 * to test the JUnit 5 extension without a real BPMN engine.
 */
package io.github.tokennudge.testsupport;
