package io.github.tokennudge.examples.delegate;

import io.github.tokennudge.examples.order.OrderStatusStore;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Marks the order matching the process instance's business key as
 * {@link io.github.tokennudge.examples.order.OrderStatus#REJECTED}. Wired into the
 * {@code payment} process's reject path (taken after the {@code risk-check} external task's
 * {@code RISK_REJECTED} boundary error) as {@code ${markRejectedDelegate}}.
 */
@Component
public class MarkRejectedDelegate implements JavaDelegate {

    private final OrderStatusStore store;

    public MarkRejectedDelegate(OrderStatusStore store) {
        this.store = store;
    }

    @Override
    public void execute(DelegateExecution execution) {
        store.markRejected(execution.getProcessBusinessKey());
    }
}
