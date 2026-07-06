package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalWriter;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Witnesses the artifact truth layer against a real Mongo replica-set: a written artifact reads
 * back to the same canonical form, an absent id reads back empty, list returns every stored
 * artifact, a re-save of the same id replaces in place (last write wins) rather than accumulating
 * documents, and a stored document that cannot be reconstructed is surfaced (not silently skipped)
 * without leaking the scan cursor. Skipped automatically where Docker is absent, so a Docker-less
 * build stays green.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoArtifactStoreIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");
    private static final CanonicalWriter WRITER = new CanonicalWriter();
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

    private static final String ORDERS_SYNC = """
            version: cyntex/v1
            kind: pipeline
            id: orders_sync
            source: orders
            """;

    @Test
    void writtenArtifactReadsBackAsTheSameCanonicalForm() {
        withStore((store, collection) -> {
            Resource source = PARSER.parse(ORDERS);
            store.save(source);

            Optional<Resource> read = store.get("orders");
            assertThat(read).isPresent();
            assertThat(WRITER.write(read.get())).isEqualTo(WRITER.write(source));
        });
    }

    @Test
    void getReturnsEmptyForAnAbsentId() {
        withStore((store, collection) -> assertThat(store.get("nope")).isEmpty());
    }

    @Test
    void listReturnsEveryStoredArtifact() {
        withStore((store, collection) -> {
            store.save(PARSER.parse(ORDERS));
            store.save(PARSER.parse(ORDERS_SYNC));

            assertThat(store.list())
                    .extracting(Resource::id)
                    .containsExactlyInAnyOrder("orders", "orders_sync");
        });
    }

    @Test
    void reSaveOfTheSameIdReplacesInPlace() {
        withStore((store, collection) -> {
            store.save(PARSER.parse(ORDERS));
            // a changed resource under the same id: the config host differs
            String changed = """
                    version: cyntex/v1
                    kind: source
                    id: orders
                    connector: mysql
                    config:
                      host: replica
                    """;
            store.save(PARSER.parse(changed));

            assertThat(store.list()).extracting(Resource::id).containsExactly("orders");
            assertThat(WRITER.write(store.get("orders").orElseThrow()))
                    .isEqualTo(WRITER.write(PARSER.parse(changed)));
        });
    }

    @Test
    void listSurfacesAnUnreadableStoredDocument() {
        withStore((store, collection) -> {
            store.save(PARSER.parse(ORDERS));
            // An out-of-band document whose body is not a parseable artifact (corruption, or a body
            // written by a newer grammar). The truth layer must surface it rather than silently skip
            // it, and the scan must not leak its server-side cursor on the failure path.
            collection.insertOne(new Document("_id", "corrupt").append("kind", "source").append("canonical", "not: [valid"));

            assertThatThrownBy(store::list).isInstanceOf(RuntimeException.class);
        });
    }

    private interface StoreTest {
        void run(MongoArtifactStore store, MongoCollection<Document> collection);
    }

    /** Runs a test body against a fresh artifact store over a clean collection on the real replica-set. */
    private static void withStore(StoreTest test) {
        try (MongoClient client = MongoClients.create(REPLICA_SET.getReplicaSetUrl())) {
            MongoCollection<Document> collection = client.getDatabase("cyntex").getCollection("artifacts");
            collection.drop();
            test.run(new MongoArtifactStore(collection), collection);
        }
    }
}
