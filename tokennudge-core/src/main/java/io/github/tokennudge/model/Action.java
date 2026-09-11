package io.github.tokennudge.model;

/**
 * An action taken against a matched {@link WaitState}.
 *
 * <p>This is a sealed hierarchy of immutable records; each permitted subtype corresponds
 * to one way of advancing (or failing) a token. As of this version, only external-task
 * actions are defined: {@link CompleteExternalTask}, {@link ThrowBpmnError}, and
 * {@link FailExternalTask}. User-task and message-correlation actions are added in a
 * later iteration.
 */
public sealed interface Action permits CompleteExternalTask, ThrowBpmnError, FailExternalTask {
}
