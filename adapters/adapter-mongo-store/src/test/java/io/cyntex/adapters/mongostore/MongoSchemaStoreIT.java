package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.cyntex.spi.store.SourceField;
import io.cyntex.spi.store.SourceIndex;
import io.cyntex.spi.store.SourceModel;
import io.cyntex.spi.store.SourceTable;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Witnesses the source-schema store against a real Mongo replica-set: a saved source model reads back
 * equal through the real bson encode / decode (tables, fields including one with no resolved type,
 * primary key, and both a unique and a non-unique index), an absent connection reads back empty, and a
 * re-discovery of the same connection replaces the stored model in place (last write wins) rather than
 * accumulating documents. Skipped automatically where Docker is absent, so a Docker-less build stays
 * green.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoSchemaStoreIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

    @Container
    private static final MongoDBContainer REPLICA_SET = new MongoDBContainer(MONGO_IMAGE);

    private static SourceModel ordersModel() {
        return new SourceModel(List.of(
                new SourceTable(
                        "orders",
                        List.of(new SourceField("id", "bigint"), new SourceField("note", null)),
                        List.of("id"),
                        List.of(
                                new SourceIndex("pk_orders", List.of("id"), true),
                                new SourceIndex("by_note", List.of("note"), false))),
                new SourceTable("customers", List.of(new SourceField("email", "varchar")), List.of("email"), List.of())));
    }

    @Test
    void savedModelReadsBackEqualThroughRealBson() {
        withStore((store, collection) -> {
            SourceModel model = ordersModel();
            store.save("orders-db", model);

            Optional<SourceModel> read = store.get("orders-db");
            assertThat(read).contains(model);
        });
    }

    @Test
    void getReturnsEmptyForAnAbsentConnection() {
        withStore((store, collection) -> assertThat(store.get("never-discovered")).isEmpty());
    }

    @Test
    void reDiscoveryReplacesTheStoredModelInPlace() {
        withStore((store, collection) -> {
            store.save("orders-db", ordersModel());
            SourceModel rediscovered = new SourceModel(List.of(
                    new SourceTable("orders", List.of(new SourceField("id", "bigint")), List.of("id"), List.of())));
            store.save("orders-db", rediscovered);

            assertThat(collection.countDocuments()).isEqualTo(1);
            assertThat(store.get("orders-db")).contains(rediscovered);
        });
    }

    private interface StoreTest {
        void run(MongoSchemaStore store, MongoCollection<Document> collection);
    }

    /** Runs a test body against a fresh schema store over a clean collection on the real replica-set. */
    private static void withStore(StoreTest test) {
        try (MongoClient client = MongoClients.create(REPLICA_SET.getReplicaSetUrl())) {
            MongoCollection<Document> collection = client.getDatabase("cyntex").getCollection("source_schemas");
            collection.drop();
            test.run(new MongoSchemaStore(collection), collection);
        }
    }
}
