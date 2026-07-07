package io.cyntex.app;

import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.StorePort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Witnesses the assembly root exposing a working store under {@code --role=all}: with the store
 * enabled and pointed at a real replica-set, the context starts, a single driver-free
 * {@code StorePort} bean is present, and it round-trips a registered connection. This is the store
 * adapter wired through the assembly root (rule R7), end to end. Skipped automatically where Docker is
 * absent, so a Docker-less build stays green.
 */
@Testcontainers(disabledWithoutDocker = true)
class StorePortWiringIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

    @Container
    private static final MongoDBContainer REPLICA_SET = new MongoDBContainer(MONGO_IMAGE);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StoreConfiguration.class);

    @Test
    void assemblyRootExposesAWorkingStorePortAgainstARealStore() {
        runner.withPropertyValues(
                        "cyntex.store.mongo.enabled=true",
                        "cyntex.store.mongo.uri=" + REPLICA_SET.getReplicaSetUrl(),
                        // the container speaks plaintext; TLS is opt-in, so no flag is needed here
                        "cyntex.store.mongo.server-selection-timeout=5s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StorePort.class);
                    StorePort store = context.getBean(StorePort.class);
                    store.catalog().save(new ConnectionConfig("mysql-local", "mysql", Map.of("host", "localhost")));
                    assertThat(store.catalog().get("mysql-local")).isPresent();
                });
    }
}
