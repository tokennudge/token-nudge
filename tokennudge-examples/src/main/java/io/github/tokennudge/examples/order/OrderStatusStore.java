package io.github.tokennudge.examples.order;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory, process-wide record of order status, written by the {@code payment} process's
 * completion delegates ({@code MarkCompletedDelegate}, {@code MarkRejectedDelegate}) and read
 * by {@code OrderController}. Not persisted; cleared on every application restart.
 */
@Component
public final class OrderStatusStore {

    private final Map<String, OrderRecord> records = new ConcurrentHashMap<>();

    /**
     * Records a new order as {@link OrderStatus#PENDING}.
     *
     * @param orderId the order id, never {@code null}
     * @param amount  the order amount, in cents
     */
    public void create(String orderId, int amount) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        records.put(orderId, new OrderRecord(orderId, OrderStatus.PENDING, amount));
    }

    /**
     * Marks an existing order as {@link OrderStatus#COMPLETED}.
     *
     * @param orderId the order id, never {@code null}
     */
    public void markCompleted(String orderId) {
        updateStatus(orderId, OrderStatus.COMPLETED);
    }

    /**
     * Marks an existing order as {@link OrderStatus#REJECTED}.
     *
     * @param orderId the order id, never {@code null}
     */
    public void markRejected(String orderId) {
        updateStatus(orderId, OrderStatus.REJECTED);
    }

    private void updateStatus(String orderId, OrderStatus status) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        records.computeIfPresent(orderId, (id, record) -> new OrderRecord(id, status, record.amount()));
    }

    /**
     * Looks up an order by id.
     *
     * @param orderId the order id, never {@code null}
     * @return the current record, or empty if no such order was ever created
     */
    public Optional<OrderRecord> find(String orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        return Optional.ofNullable(records.get(orderId));
    }
}
