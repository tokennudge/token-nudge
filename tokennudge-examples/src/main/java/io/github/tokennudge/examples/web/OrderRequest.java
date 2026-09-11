package io.github.tokennudge.examples.web;

/**
 * Request body for {@code POST /orders}.
 *
 * @param amount the order amount, in cents
 */
public record OrderRequest(int amount) {
}
