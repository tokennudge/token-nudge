package io.github.tokennudge.camunda7;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;

/**
 * A Camunda 7 / CIB Seven "Run" distribution container, started once per JVM and shared by
 * all integration tests (starting the engine takes several seconds; restarting it per test
 * class would dominate the test run).
 */
final class EngineContainer extends GenericContainer<EngineContainer> {

    private static final int PORT = 8080;

    private static volatile EngineContainer instance;

    private EngineContainer() {
        super(EngineImages.image());
        withExposedPorts(PORT);
        withCommand(EngineImages.command());
        waitingFor(Wait.forHttp("/engine-rest/engine")
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /**
     * Returns the shared, already-started container instance, starting it on first use.
     *
     * @return the running engine container
     */
    static synchronized EngineContainer sharedInstance() {
        if (instance == null) {
            EngineContainer container = new EngineContainer();
            container.start();
            instance = container;
        }
        return instance;
    }

    /**
     * Returns the engine-rest base URL for this container, for example
     * {@code http://localhost:32768/engine-rest}.
     *
     * @return the engine-rest base URL, without a trailing slash
     */
    String baseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(PORT) + "/engine-rest";
    }
}
