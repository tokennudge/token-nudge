package io.github.tokennudge.model;

/**
 * An action taken against a matched {@link WaitState}.
 *
 * <p>This is a sealed hierarchy of immutable records; each permitted subtype corresponds
 * to one way of advancing (or failing) a token: the external-task actions
 * {@link CompleteExternalTask}, {@link ThrowBpmnError}, and {@link FailExternalTask}, the
 * user-task action {@link CompleteUserTask}, and the message-correlation action
 * {@link CorrelateMessage}.
 */
public sealed interface Action
        permits CompleteExternalTask, ThrowBpmnError, FailExternalTask, CompleteUserTask, CorrelateMessage {
}
