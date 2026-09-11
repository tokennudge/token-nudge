package io.github.tokennudge.examples.web;

import io.github.tokennudge.examples.order.OrderStatus;

/**
 * Response body for {@code POST /orders} and {@code GET /orders/{id}}.
 *
 * @param orderId the order id
 * @param status  the current status
 * @param amount  the order amount, in cents
 */
public record OrderResponse(String orderId, OrderStatus status, int amount) {
}
