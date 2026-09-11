package io.github.tokennudge.internal;

import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;

/**
 * Test fixture factory for {@link WaitState} instances used by {@link NudgeLoopTest} and
 * friends (not a test class itself).
 */
final class LoopWaitStates {

    private LoopWaitStates() {
    }

    static WaitState externalTask(String id, String topic) {
        return new WaitState(
                WaitStateKind.EXTERNAL_TASK, id, topic, "pi-" + id, "payment", "act-" + id, "order-" + id,
                "ex-" + id, null);
    }
}
