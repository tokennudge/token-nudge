package io.github.tokennudge.junit5;

import io.github.tokennudge.NudgeOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import static io.github.tokennudge.TokenNudge.externalTask;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compile-level test mirroring {@code tokennudge-brief.md}'s usage of {@code TokenNudgeExtension}
 * with the static DSL imports. {@link BriefUsageExample} is never actually registered/run by
 * JUnit (it exists only so the brief's exact declaration compiles against the real API); this
 * class only inspects it via reflection and asserts the extension's API shape, without ever
 * starting anything. Real end-to-end usage against a running engine is exercised by the
 * examples module, not here.
 */
class TokenNudgeExtensionBriefUsageTest {

    @Test
    void theBriefsStaticExtensionFieldDeclarationCompilesAsWritten() throws NoSuchFieldException {
        Field field = BriefUsageExample.class.getDeclaredField("nudge");

        assertThat(field.getType()).isEqualTo(TokenNudgeExtension.class);
        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(field.isAnnotationPresent(RegisterExtension.class)).isTrue();
    }

    @Test
    void theExtensionImplementsEveryContractTheBriefRelivesOn() {
        assertThat(TokenNudgeExtension.class.getInterfaces()).contains(
                NudgeOperations.class,
                BeforeAllCallback.class,
                AfterAllCallback.class,
                BeforeEachCallback.class,
                AfterEachCallback.class,
                ParameterResolver.class);
    }

    /**
     * Mirrors {@code tokennudge-brief.md} exactly (down to the static DSL imports used inside
     * the never-executed {@code @Test} method below). Never selected/executed by any test
     * engine, so {@code TokenNudgeExtension.forEngine(...)}'s lazy start against
     * {@code localhost:8080} is never actually attempted.
     */
    static class BriefUsageExample {

        @RegisterExtension
        static TokenNudgeExtension nudge = TokenNudgeExtension.forEngine("http://localhost:8080/engine-rest");

        @Test
        void paymentIsChargedAndCompletes() {
            nudge.simulate(externalTask("charge-card").willComplete(withVariables(Map.of("paid", true))));
            nudge.verify(externalTask("charge-card").completed().times(1));
        }
    }
}
