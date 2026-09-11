package io.github.tokennudge.camunda7;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.spi.ClaimResult;
import io.github.tokennudge.spi.DiscoveryQuery;
import io.github.tokennudge.spi.EngineAdapter;
import io.github.tokennudge.spi.EngineConfig;

import java.util.List;
import java.util.Map;

/**
 * Test-only {@link EngineAdapter} wrapping a real {@link Camunda7EngineAdapter}, used to
 * reproduce a deterministic, real-engine 4xx rejection of an {@code execute} call: it deletes
 * the wait state's process instance right before delegating to the real adapter's
 * {@code execute}, so the engine rejects the already-claimed action with a {@code 404}
 * "does not exist" &mdash; the same response shape an already-completed or otherwise vanished
 * external task produces.
 *
 * <p>Used only by {@code Camunda7EngineAdapterFailureIT}'s
 * {@code engineRejectedActionEndsUpInActionErrors} test; documented there as one of the
 * deliberately chosen, deterministic ways to get the engine to reject an action outright.
 */
final class DeleteProcessInstanceBeforeExecuteAdapter implements EngineAdapter {

    private final Camunda7EngineAdapter delegate;
    private final EngineRestTestClient testClient;

    DeleteProcessInstanceBeforeExecuteAdapter(EngineConfig config, EngineRestTestClient testClient) {
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
        testClient.deleteProcessInstance(waitState.processInstanceId());
        delegate.execute(waitState, action);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
