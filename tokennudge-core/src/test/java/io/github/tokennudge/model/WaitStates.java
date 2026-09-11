package io.github.tokennudge.model;

/**
 * Test fixture factory for {@link WaitState} instances (not a test class itself).
 */
final class WaitStates {

    private WaitStates() {
    }

    static WaitState externalTask(String topic) {
        return new WaitState(
                WaitStateKind.EXTERNAL_TASK,
                "task-1",
                topic,
                "process-instance-1",
                "payment",
                "charge-card-activity",
                "order-42",
                "execution-1",
                null);
    }

    static WaitState externalTask() {
        return externalTask("charge-card");
    }
}
