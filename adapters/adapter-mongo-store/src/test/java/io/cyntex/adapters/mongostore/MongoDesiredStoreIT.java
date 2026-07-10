package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.cyntex.core.lifecycle.DesiredState;
import io.cyntex.core.lifecycle.PipelineState;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Witnesses the pipeline desired-state store against a real Mongo replica-set: a saved desired intent
 * reads back equal through the real bson encode / decode, an absent pipeline reads back empty, and a
 * re-save of the same pipeline replaces in place (last write wins) rather than accumulating documents.
 * Skipped automatically where Docker is absent, so a Docker-less build stays green.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoDesiredStoreIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

    @Container
    private static final MongoDBContainer REPLICA_SET = new MongoDBContainer(MONGO_IMAGE);

    @Test
    void savedDesiredReadsBackEqual() {
        withStore((store, collection) -> {
            DesiredState want = new DesiredState("orders_sync", PipelineState.RUNNING, "rev-abc");
            store.save(want);

            Optional<DesiredState> read = store.read("orders_sync");
            assertThat(read).contains(want);
        });
    }

    @Test
    void readReturnsEmptyForAnAbsentPipeline() {
        withStore((store, collection) -> assertThat(store.read("nope")).isEmpty());
    }

    @Test
    void reSaveOfTheSamePipelineReplacesInPlace() {
        withStore((store, collection) -> {
            store.save(new DesiredState("orders_sync", PipelineState.RUNNING, "rev-1"));
            DesiredState changed = new DesiredState("orders_sync", PipelineState.STOPPED, "rev-2");
            store.save(changed);

            assertThat(collection.countDocuments()).isEqualTo(1);
            assertThat(store.read("orders_sync")).contains(changed);
        });
    }

    private interface StoreTest {
        void run(MongoDesiredStore store, MongoCollection<Document> collection);
    }

    /** Runs a test body against a fresh desired store over a clean collection on the real replica-set. */
    private static void withStore(StoreTest test) {
        try (MongoClient client = MongoClients.create(REPLICA_SET.getReplicaSetUrl())) {
            MongoCollection<Document> collection = client.getDatabase("cyntex").getCollection("pipeline_desired");
            collection.drop();
            test.run(new MongoDesiredStore(collection), collection);
        }
    }
}
