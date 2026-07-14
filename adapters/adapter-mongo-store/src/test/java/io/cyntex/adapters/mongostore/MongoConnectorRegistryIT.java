package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import io.cyntex.spi.store.ConnectorRegistration;
import io.cyntex.spi.store.RegistrationOutcome;
import io.cyntex.spi.store.RegistrationSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Witnesses the connector registry against a real Mongo replica-set through the real GridFS store: a
 * registered artifact reads back with its identity and its exact bytes, re-registering the same bytes is
 * a content-hash no-op that stores no second copy, list returns every registration, and an unknown hash
 * has no bytes. Skipped automatically where Docker is absent, so a Docker-less build stays green.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoConnectorRegistryIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

    @Container
    private static final MongoDBContainer REPLICA_SET = new MongoDBContainer(MONGO_IMAGE);

    private static byte[] jar(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void registerStoresTheRegistrationAndRetrievableBytes() {
        withRegistry(registry -> {
            byte[] jar = jar("mysql-connector-bytes");
            RegistrationOutcome outcome = registry.register("mysql", "1.3.5", RegistrationSource.REGISTER, jar);

            assertThat(outcome.newlyRegistered()).isTrue();
            ConnectorRegistration registration = outcome.registration();
            assertThat(registration.connectorId()).isEqualTo("mysql");
            assertThat(registration.pdkApiVersion()).isEqualTo("1.3.5");
            assertThat(registration.source()).isEqualTo(RegistrationSource.REGISTER);
            assertThat(registry.artifact(registration.contentHash()).orElseThrow()).isEqualTo(jar);
        });
    }

    @Test
    void reRegisteringTheSameBytesIsAContentHashNoOp() {
        withRegistry(registry -> {
            byte[] jar = jar("mysql-connector-bytes");
            RegistrationOutcome first = registry.register("mysql", "1.3.5", RegistrationSource.SEED, jar);
            RegistrationOutcome again = registry.register("mysql", "1.3.5", RegistrationSource.REGISTER, jar);

            assertThat(first.newlyRegistered()).isTrue();
            assertThat(again.newlyRegistered()).isFalse();
            // the no-op returns what is stored (the original SEED source), and stores no second copy
            assertThat(again.registration()).isEqualTo(first.registration());
            assertThat(registry.list()).hasSize(1);
        });
    }

    @Test
    void listReturnsEveryRegisteredConnector() {
        withRegistry(registry -> {
            registry.register("mysql", "1.3.5", RegistrationSource.SEED, jar("mysql-bytes"));
            registry.register("postgres", "1.3.5", RegistrationSource.REGISTER, jar("postgres-bytes"));

            assertThat(registry.list())
                    .extracting(ConnectorRegistration::connectorId)
                    .containsExactlyInAnyOrder("mysql", "postgres");
        });
    }

    @Test
    void artifactForAnUnknownHashIsEmpty() {
        withRegistry(registry -> assertThat(registry.artifact("deadbeef")).isEmpty());
    }

    private interface RegistryTest {
        void run(MongoConnectorRegistry registry);
    }

    /** Runs a test body against a fresh registry over a clean GridFS bucket on the real replica-set. */
    private static void withRegistry(RegistryTest test) {
        try (MongoClient client = MongoClients.create(REPLICA_SET.getReplicaSetUrl())) {
            MongoDatabase database = client.getDatabase("cyntex");
            GridFSBucket bucket = GridFSBuckets.create(database, "connector_artifacts");
            bucket.drop();
            test.run(new MongoConnectorRegistry(bucket));
        }
    }
}
