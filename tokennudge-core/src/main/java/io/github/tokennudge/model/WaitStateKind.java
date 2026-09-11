package io.github.tokennudge.model;

/**
 * The kind of BPMN wait state a {@link WaitState} represents.
 */
public enum WaitStateKind {

    /** An external task, identified by its topic name. */
    EXTERNAL_TASK,

    /** A (human) user task, identified by its task definition key. */
    USER_TASK,

    /** A message catch event or receive task subscription, identified by the message name. */
    MESSAGE_SUBSCRIPTION
}
