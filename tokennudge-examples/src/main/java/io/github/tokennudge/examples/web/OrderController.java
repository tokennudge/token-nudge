package io.github.tokennudge.examples.web;

import io.github.tokennudge.examples.order.OrderRecord;
import io.github.tokennudge.examples.order.OrderStatus;
import io.github.tokennudge.examples.order.OrderStatusStore;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The example service's public HTTP API: start a {@code payment} process for a new order, and
 * read back its current status. This is the only surface a black-box test may call directly -
 * the embedded engine itself is only ever nudged through TokenNudge, from test code.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final RuntimeService runtimeService;
    private final OrderStatusStore store;

    public OrderController(RuntimeService runtimeService, OrderStatusStore store) {
        this.runtimeService = runtimeService;
        this.store = store;
    }

    /**
     * Starts a new {@code payment} process instance for a freshly minted order id, used as the
     * process instance's business key.
     *
     * @param request the order amount
     * @return {@code 202 Accepted} with the new order's id and {@link OrderStatus#PENDING} status
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        store.create(orderId, request.amount());
        runtimeService.startProcessInstanceByKey("payment", orderId, Map.of("amount", request.amount()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new OrderResponse(orderId, OrderStatus.PENDING, request.amount()));
    }

    /**
     * Reads back an order's current status.
     *
     * @param orderId the order id returned by {@link #createOrder(OrderRequest)}
     * @return {@code 200 OK} with the order, or {@code 404 Not Found} if no such order exists
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderId) {
        return store.find(orderId)
                .map(OrderController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static OrderResponse toResponse(OrderRecord record) {
        return new OrderResponse(record.orderId(), record.status(), record.amount());
    }
}
