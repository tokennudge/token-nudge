package io.github.tokennudge.camunda7;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.model.WaitStateKind;
import io.github.tokennudge.spi.ClaimResult;
import io.github.tokennudge.spi.DiscoveryQuery;
import io.github.tokennudge.spi.EngineAdapter;
import io.github.tokennudge.spi.EngineConfig;

import java.util.List;
import java.util.Map;

/**
 * Test-only {@link EngineAdapter} wrapping a real {@link Camunda7EngineAdapter}, used to
 * reproduce a deterministic, real-engine "lost race" for a user task: it completes the wait
 * state's user task directly through {@link EngineRestTestClient} right before delegating to
 * the real adapter's {@code execute}, so the delegate's own completion request is rejected by
 * the engine with a {@code 500} "Cannot find task with id ..." &mdash; exactly the shape another worker, a
 * human through some other UI, or the process itself completing the task first would produce.
 *
 * <p>Only wraps {@link WaitStateKind#USER_TASK} wait states; any other kind is delegated
 * unchanged (not needed by the one test using this class, but keeps the wrapper honest about
 * what it does).
 *
 * <p>Used only by {@code Camunda7UserTaskIT}'s
 * {@code aUserTaskCompletedOutOfBandBetweenDiscoveryAndExecuteIsJournaledClaimLost} test.
 */
final class CompleteUserTaskBeforeExecuteAdapter implements EngineAdapter {

    private final Camunda7EngineAdapter delegate;
    private final EngineRestTestClient testClient;

    CompleteUserTaskBeforeExecuteAdapter(EngineConfig config, EngineRestTestClient testClient) {
        this.delegate = new Camunda7EngineAdapter(config);
        this.testClient = testClient;
    }

    @Override
    public void checkConnectivity() {
        delegate.checkConnectivity();
    }

    @Override
    public List<WaitState> discover(DiscoveryQuery query) {
        return delegate.discover(query);
    }

    @Override
    public ClaimResult claim(WaitState waitState) {
        return delegate.claim(waitState);
    }

    @Override
    public Map<String, Object> variables(WaitState waitState) {
        return delegate.variables(waitState);
    }

    @Override
    public void execute(WaitState waitState, Action action) {
        if (waitState.kind() == WaitStateKind.USER_TASK) {
            testClient.completeTask(waitState.id());
        }
        delegate.execute(waitState, action);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
