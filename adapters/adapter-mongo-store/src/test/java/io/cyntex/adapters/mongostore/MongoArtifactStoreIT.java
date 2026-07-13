package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalHash;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.spi.store.ArtifactMutation;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Witnesses the artifact truth layer against a real Mongo replica-set: a written artifact reads
 * back to the same canonical form, an absent id reads back empty, list returns every stored
 * artifact, a re-save of the same id replaces in place (last write wins) rather than accumulating
 * documents, a batch write commits atomically and rolls the whole batch back on a mid-batch write
 * failure, and a stored document that cannot be reconstructed is surfaced (not silently skipped)
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
    void versionedMutationsAreAtomicAndStaleWritesLeaveCanonicalBytesUnchanged() {
        withStore((store, collection) -> {
            Resource source = PARSER.parse(ORDERS);
            Resource changed = PARSER.parse("""
                    version: cyntex/v1
                    kind: source
                    id: orders
                    connector: mysql
                    config:
                      host: replica
                    """);
            Resource changedAgain = PARSER.parse("""
                    version: cyntex/v1
                    kind: source
                    id: orders
                    connector: mysql
                    config:
                      host: stale-writer
                    """);
            Resource differentId = PARSER.parse("""
                    version: cyntex/v1
                    kind: source
                    id: customers
                    connector: mysql
                    config:
                      host: replica
                    """);
            String oldHash = CanonicalHash.of(WRITER.write(source));
            String newHash = CanonicalHash.of(WRITER.write(changed));

            assertThat(store.create(source)).isEqualTo(ArtifactMutation.CREATED);
            assertThat(store.create(source)).isEqualTo(ArtifactMutation.ALREADY_EXISTS);
            assertThat(store.replace("orders", oldHash, changed)).isEqualTo(ArtifactMutation.REPLACED);

            String canonicalAfterReplace = collection.find(new Document("_id", "orders"))
                    .first().getString("canonical");
            assertThat(store.replace("orders", oldHash, changedAgain))
                    .isEqualTo(ArtifactMutation.VERSION_CONFLICT);
            assertThat(collection.find(new Document("_id", "orders")).first().getString("canonical"))
                    .isEqualTo(canonicalAfterReplace);

            assertThatThrownBy(() -> store.replace("orders", newHash, differentId))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(collection.find(new Document("_id", "orders")).first().getString("canonical"))
                    .isEqualTo(canonicalAfterReplace);

            assertThat(store.delete("orders", oldHash)).isEqualTo(ArtifactMutation.VERSION_CONFLICT);
            assertThat(collection.find(new Document("_id", "orders")).first().getString("canonical"))
                    .isEqualTo(canonicalAfterReplace);

            assertThat(store.delete("orders", newHash)).isEqualTo(ArtifactMutation.DELETED);
            assertThat(store.delete("orders", newHash)).isEqualTo(ArtifactMutation.NOT_FOUND);
        });
    }

    @Test
    void saveAllCommitsTheWholeBatchAtomically() {
        withStore((store, collection) -> {
            store.saveAll(List.of(PARSER.parse(ORDERS), PARSER.parse(ORDERS_SYNC)));

            assertThat(store.list())
                    .extracting(Resource::id)
                    .containsExactlyInAnyOrder("orders", "orders_sync");
        });
    }

    @Test
    void saveAllRollsBackTheWholeBatchOnAWriteFailure() {
        withStore((store, collection) -> {
            // Force a genuine mid-transaction write error: a unique index on the (non-unique-in-reality)
            // kind field makes the second same-kind upsert in the batch collide, so the driver fails the
            // write inside the transaction. The whole batch must then roll back — not even the first,
            // earlier-ordered resource is left behind.
            collection.createIndex(new Document("kind", 1), new IndexOptions().unique(true));
            String orders2 = """
                    version: cyntex/v1
                    kind: source
                    id: orders2
                    connector: mysql
                    config:
                      host: localhost
                    """;

            Throwable thrown = catchThrowable(() ->
                    store.saveAll(List.of(PARSER.parse(ORDERS), PARSER.parse(orders2))));

            assertThat(thrown).isInstanceOf(CyntexException.class);
            assertThat(store.list()).as("the whole batch rolled back — nothing was stored").isEmpty();
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

            Throwable thrown = catchThrowable(store::list);
            assertThat(thrown).isInstanceOf(CyntexException.class);
            assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
            assertThat(((CyntexException) thrown).args()).containsEntry("id", "corrupt");
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
            test.run(new MongoArtifactStore(client, collection), collection);
        }
    }
}
