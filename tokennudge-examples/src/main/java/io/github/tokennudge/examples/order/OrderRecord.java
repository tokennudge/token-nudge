package io.github.tokennudge.examples.order;

/**
 * An immutable snapshot of an order, as tracked in memory by {@link OrderStatusStore}.
 *
 * @param orderId the order id, also used as the {@code payment} process instance's business key
 * @param status  the current status
 * @param amount  the order amount, in cents
 */
public record OrderRecord(String orderId, OrderStatus status, int amount) {
}
