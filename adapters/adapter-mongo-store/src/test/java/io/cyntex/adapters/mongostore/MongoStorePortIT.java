package io.cyntex.adapters.mongostore;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.ConnectionTestItem;
import io.cyntex.spi.store.ConnectionTestResult;
import io.cyntex.spi.store.RegistrationSource;
import io.cyntex.spi.store.SourceModel;
import io.cyntex.spi.store.SourceTable;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Witnesses the aggregated store port against a real Mongo replica-set: one write through each of the
 * sub-stores lands in its own distinct, named storage area and reads back through that same sub-store,
 * so the artifact truth layer, the pipeline state store, the connection catalog, the discovered
 * source-schema store, the connector distribution registry and the latest connection-test result per
 * connection never share storage. Skipped automatically where Docker is absent, so a Docker-less build
 * stays green.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoStorePortIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");
    private static final DslParser PARSER = new DslParser();

    @Container
    private static final MongoDBContainer REPLICA_SET = new MongoDBContainer(MONGO_IMAGE);

    private static final String ORDERS = """
            version: cyntex/v1
            kind: source
            id: orders
            connector: mysql
            config:
              host: localhost
            """;

    @Test
    void aggregatesTheSixSubStoresEachOnItsOwnStorage() {
        // The Testcontainers Mongo speaks plaintext; TLS is opt-in, so a plaintext URL connects with
        // no flag. TLS wiring itself is covered by MongoConnectionTest.
        String uri = REPLICA_SET.getReplicaSetUrl();
        MongoConnectionSettings settings = new MongoConnectionSettings(uri, null, Duration.ofSeconds(5));
        try (MongoConnection connection = new MongoConnection(settings)) {
            connection.verify();
            MongoStorePort port = new MongoStorePort(connection);

            // one write through each of the six sub-stores
            port.artifacts().save(PARSER.parse(ORDERS));
            port.state().create("orders_sync", "{\"phase\":\"snapshot\"}", Instant.parse("2026-07-06T00:00:00Z"));
            port.catalog().save(new ConnectionConfig("mysql-local", "mysql", Map.of("host", "localhost")));
            port.schemas().save("mysql-local", new SourceModel(List.of(
                    new SourceTable("orders", List.of(), List.of(), List.of()))));
            port.connectors().register(
                    "mysql", "1.3.5", RegistrationSource.SEED, "mysql-connector-bytes".getBytes(StandardCharsets.UTF_8));
            port.connectionTestResults().save(new ConnectionTestResult(
                    "mysql-local",
                    "mysql",
                    ConnectionTestResult.Outcome.PASSED,
                    List.of(new ConnectionTestItem("Connection", ConnectionTestItem.Status.PASSED, null, null, null, null)),
                    1783939200000L));

            // each reads back through its own sub-store
            assertThat(port.artifacts().get("orders")).isPresent();
            assertThat(port.state().read("orders_sync")).isPresent();
            assertThat(port.catalog().get("mysql-local")).isPresent();
            assertThat(port.schemas().get("mysql-local")).isPresent();
            assertThat(port.connectors().list()).hasSize(1);
            assertThat(port.connectionTestResults().find("mysql-local")).isPresent();

            // and each landed in its own distinct, named storage — no concern shares storage
            String databaseName = new ConnectionString(uri).getDatabase();
            try (MongoClient raw = MongoClients.create(uri)) {
                MongoDatabase database = raw.getDatabase(databaseName);
                assertThat(database.getCollection(MongoStorePort.ARTIFACTS).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.PIPELINE_STATE).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.CONNECTIONS).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.SOURCE_SCHEMAS).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.CONNECTOR_ARTIFACTS + ".files").countDocuments())
                        .isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.CONNECTION_TEST_RESULTS).countDocuments())
                        .isEqualTo(1);
            }
        }
    }
}
