package io.github.tokennudge.junit5;

import io.github.tokennudge.model.Action;
import io.github.tokennudge.model.WaitState;
import io.github.tokennudge.spi.ClaimResult;
import io.github.tokennudge.spi.DiscoveryQuery;
import io.github.tokennudge.spi.EngineAdapter;
import io.github.tokennudge.testsupport.FakeEngineAdapter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a {@link FakeEngineAdapter} and counts {@link #checkConnectivity()} and
 * {@link #close()} calls, so a test can assert exactly how many times a
 * {@code TokenNudgeExtension}-managed {@code TokenNudge} was actually started/stopped (for
 * example: once per class in static mode vs. once per test in instance mode).
 */
final class CountingEngineAdapter implements EngineAdapter {

    private final FakeEngineAdapter delegate = new FakeEngineAdapter();
    private final AtomicInteger connectivityChecks = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();

    FakeEngineAdapter delegate() {
        return delegate;
    }

    int connectivityChecks() {
        return connectivityChecks.get();
    }

    int closes() {
        return closes.get();
    }

    @Override
    public void checkConnectivity() {
        connectivityChecks.incrementAndGet();
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
        delegate.execute(waitState, action);
    }

    @Override
    public void close() {
        closes.incrementAndGet();
        delegate.close();
    }
}
