package io.github.tokennudge.examples.order;

/**
 * The status of an order, as tracked by {@link OrderStatusStore}.
 */
public enum OrderStatus {

    /** The {@code payment} process has started but not yet reached an end event. */
    PENDING,

    /** The card was charged and {@code payment} ended on its happy path. */
    COMPLETED,

    /** The risk check rejected the order and {@code payment} ended on its reject path. */
    REJECTED
}
