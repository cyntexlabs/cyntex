package io.cyntex.adapters.mongostore;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.spi.store.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Witnesses the aggregated store port against a real Mongo replica-set: one write through each of the
 * three sub-stores lands in its own distinct, named collection and reads back through that same
 * sub-store, so the artifact truth layer, the pipeline state store and the connection catalog never
 * share storage. Skipped automatically where Docker is absent, so a Docker-less build stays green.
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
    void aggregatesTheThreeSubStoresEachOnItsOwnCollection() {
        // The Testcontainers Mongo speaks plaintext, so the plaintext connection is opted into
        // explicitly; the TLS-required guard is covered by MongoConnectionTest / StoreStartupTest.
        String uri = REPLICA_SET.getReplicaSetUrl();
        MongoConnectionSettings settings = new MongoConnectionSettings(uri, true, null, Duration.ofSeconds(5));
        try (MongoConnection connection = new MongoConnection(settings)) {
            connection.verify();
            MongoStorePort port = new MongoStorePort(connection);

            // one write through each of the three sub-stores
            port.artifacts().save(PARSER.parse(ORDERS));
            port.state().create("orders_sync", "{\"phase\":\"snapshot\"}", Instant.parse("2026-07-06T00:00:00Z"));
            port.catalog().save(new ConnectionConfig("mysql-local", "mysql", Map.of("host", "localhost")));

            // each reads back through its own sub-store
            assertThat(port.artifacts().get("orders")).isPresent();
            assertThat(port.state().read("orders_sync")).isPresent();
            assertThat(port.catalog().get("mysql-local")).isPresent();

            // and each landed in its own distinct, named collection — no concern shares storage
            String databaseName = new ConnectionString(uri).getDatabase();
            try (MongoClient raw = MongoClients.create(uri)) {
                MongoDatabase database = raw.getDatabase(databaseName);
                assertThat(database.getCollection(MongoStorePort.ARTIFACTS).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.PIPELINE_STATE).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.CONNECTIONS).countDocuments()).isEqualTo(1);
            }
        }
    }
}
