package io.cyntex.adapters.mongostore;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.lifecycle.DesiredState;
import io.cyntex.core.lifecycle.Observation;
import io.cyntex.core.lifecycle.PipelineState;
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
 * six sub-stores lands in its own distinct, named collection and reads back through that same
 * sub-store, so the artifact truth layer, the pipeline state store, the pipeline desired-state store,
 * the per-pipeline observation store, the connection catalog and the SRS meta store never share
 * storage. Skipped automatically where Docker is absent, so a Docker-less build stays green.
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
    void aggregatesTheSixSubStoresEachOnItsOwnCollection() {
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
            port.desired().save(new DesiredState("orders_sync", PipelineState.RUNNING, "rev-abc"));
            port.observations().save(new Observation("orders_sync", PipelineState.RUNNING, Map.of(), Map.of()));
            port.catalog().save(new ConnectionConfig("mysql-local", "mysql", Map.of("host", "localhost")));
            port.meta().create("orders@mysql-1", "7d");

            // each reads back through its own sub-store
            assertThat(port.artifacts().get("orders")).isPresent();
            assertThat(port.state().read("orders_sync")).isPresent();
            assertThat(port.desired().read("orders_sync")).isPresent();
            assertThat(port.observations().read("orders_sync")).isPresent();
            assertThat(port.catalog().get("mysql-local")).isPresent();
            assertThat(port.meta().read("orders@mysql-1")).isPresent();

            // and each landed in its own distinct, named collection — no concern shares storage
            String databaseName = new ConnectionString(uri).getDatabase();
            try (MongoClient raw = MongoClients.create(uri)) {
                MongoDatabase database = raw.getDatabase(databaseName);
                assertThat(database.getCollection(MongoStorePort.ARTIFACTS).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.PIPELINE_STATE).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.PIPELINE_DESIRED).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.PIPELINE_OBSERVATION).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.CONNECTIONS).countDocuments()).isEqualTo(1);
                assertThat(database.getCollection(MongoStorePort.SRS_META).countDocuments()).isEqualTo(1);
            }
        }
    }
}
