package io.github.tokennudge.examples;

import io.github.tokennudge.examples.order.OrderStatus;
import io.github.tokennudge.examples.web.OrderRequest;
import io.github.tokennudge.examples.web.OrderResponse;
import io.github.tokennudge.junit5.TokenNudgeExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import static io.github.tokennudge.TokenNudge.businessKey;
import static io.github.tokennudge.TokenNudge.externalTask;
import static io.github.tokennudge.TokenNudge.message;
import static io.github.tokennudge.TokenNudge.userTask;
import static io.github.tokennudge.TokenNudge.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Black-box tests for the {@code payment} process, driven entirely through the example
 * service's own public HTTP API ({@code POST /orders}, {@code GET /orders/{id}}) plus
 * TokenNudge. Neither test touches the embedded engine or the order store directly.
 *
 * <p>Both test methods share one {@link TokenNudgeExtension} static field, so, per its class
 * Javadoc, this class stays on {@link ExecutionMode#SAME_THREAD} to avoid one test's
 * {@code afterEach} reset racing with the other's still-running simulate/verify calls.
 */
@SpringBootTest(
        classes = TokenNudgeExamplesApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Execution(ExecutionMode.SAME_THREAD)
class PaymentFlowIT {

    private static final int PORT = findFreePort();
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void registerPort(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
    }

    @RegisterExtension
    static final TokenNudgeExtension nudge =
            TokenNudgeExtension.forEngine(() -> "http://localhost:" + PORT + "/engine-rest")
                    .pollInterval(Duration.ofMillis(50));

    @Autowired
    private TestRestTemplate restTemplate;

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("could not find a free port for the example app", e);
        }
    }

    @Test
    void happyPathChargesTheCardConfirmsPaymentApprovesShipmentAndMarksTheOrderCompleted() {
        nudge.simulate(externalTask("risk-check").inProcess("payment").willComplete());
        nudge.simulate(externalTask("charge-card").inProcess("payment")
                .willComplete(withVariables(Map.of("authorized", true))));
        nudge.simulate(message("PaymentConfirmed").inProcess("payment").willCorrelateBy(businessKey()));
        nudge.simulate(userTask("approve-shipment").inProcess("payment").willComplete());

        String orderId = createOrder(4200);

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo(OrderStatus.COMPLETED));

        nudge.verify(externalTask("charge-card").completed().times(1).withVariable("amount", 4200));
        // reached()/never() only means anything for a wait state some rule covers (see the
        // TokenNudge only polls what a rule covers); both are covered by the simulations
        // registered above, so these assert the new steps were actually driven by TokenNudge,
        // not just skipped past.
        nudge.verify(message("PaymentConfirmed").correlated().times(1));
        nudge.verify(userTask("approve-shipment").completed().times(1));
    }

    @Test
    void rejectedPathNeverReachesChargeCardAndMarksTheOrderRejected() {
        nudge.simulate(externalTask("risk-check").inProcess("payment").willFailWithBpmnError("RISK_REJECTED"));
        // Register charge-card as well, even though the rejected path must never get there:
        // TokenNudge only polls topics that have at least one rule, so without this the
        // reached().never() check below would pass no matter what the process did.
        nudge.simulate(externalTask("charge-card").inProcess("payment").willComplete());

        String orderId = createOrder(4200);

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo(OrderStatus.REJECTED));

        nudge.verify(externalTask("charge-card").reached().never());
    }

    private String createOrder(int amount) {
        ResponseEntity<OrderResponse> response =
                restTemplate.postForEntity("/orders", new OrderRequest(amount), OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody().orderId();
    }

    private OrderResponse getOrder(String orderId) {
        return restTemplate.getForObject("/orders/{orderId}", OrderResponse.class, orderId);
    }
}
