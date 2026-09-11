package io.github.tokennudge.examples.delegate;

import io.github.tokennudge.examples.order.OrderStatusStore;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Marks the order matching the process instance's business key as
 * {@link io.github.tokennudge.examples.order.OrderStatus#COMPLETED}. Wired into the
 * {@code payment} process's happy path as {@code ${markCompletedDelegate}}.
 */
@Component
public class MarkCompletedDelegate implements JavaDelegate {

    private final OrderStatusStore store;

    public MarkCompletedDelegate(OrderStatusStore store) {
        this.store = store;
    }

    @Override
    public void execute(DelegateExecution execution) {
        store.markCompleted(execution.getProcessBusinessKey());
    }
}
