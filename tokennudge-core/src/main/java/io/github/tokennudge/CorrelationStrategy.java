package io.github.tokennudge;

/**
 * How a {@link io.github.tokennudge.model.CorrelateMessage} action targets the process
 * instance it correlates a message to.
 *
 * <p>Instances are created via {@link TokenNudge#processInstance()} and
 * {@link TokenNudge#businessKey()}; neither carries the target id or key itself, since that
 * is only known once a message wait state is actually matched (see
 * {@link MessageSpec#willCorrelateBy(CorrelationStrategy)}).
 */
public sealed interface CorrelationStrategy permits CorrelationStrategy.ByProcessInstance, CorrelationStrategy.ByBusinessKey {

    /**
     * Correlates by the matched wait state's process instance id. This is the default used
     * by {@link MessageSpec#willCorrelate()}.
     */
    record ByProcessInstance() implements CorrelationStrategy {
    }

    /**
     * Correlates by the matched wait state's process instance's business key. Fails with a
     * clear {@link io.github.tokennudge.spi.EngineActionException} if the wait state has no
     * business key.
     */
    record ByBusinessKey() implements CorrelationStrategy {
    }
}
