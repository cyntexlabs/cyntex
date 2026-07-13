package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.cyntex.spi.store.ConsumerOffset;
import io.cyntex.spi.store.SchemaVersion;
import io.cyntex.spi.store.SrsMeta;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Witnesses the SRS meta store's atomic operations against a real Mongo replica-set: a seed lands an
 * empty record, a re-seed is refused (insert-only, so the accumulated truth is never discarded), the
 * source read offset and cdc start position advance, a consumer cursor is inserted then replaced in
 * place by pipeline id, the schema history appends in order, and a mutate on an unseeded chain is an
 * ordering error. Skipped automatically where Docker is absent, so a Docker-less build stays green.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoSrsMetaStoreIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");
    private static final String CHAIN = "orders@mysql-1";

    @Container
    private static final MongoDBContainer REPLICA_SET = new MongoDBContainer(MONGO_IMAGE);

    @Test
    void createSeedsAnEmptyRecordAndReadReturnsIt() {
        withStore(store -> {
            store.create(CHAIN, "7d");

            SrsMeta seeded = store.read(CHAIN).orElseThrow();
            assertThat(seeded.miningChainId()).isEqualTo(CHAIN);
            assertThat(seeded.retention()).isEqualTo("7d");
            assertThat(seeded.sourceReadOffset()).isNull();
            assertThat(seeded.cdcStartPosition()).isNull();
            assertThat(seeded.consumerOffsets()).isEmpty();
            assertThat(seeded.schemaHistory()).isEmpty();
        });
    }

    @Test
    void readReturnsEmptyForAnUnminedChain() {
        withStore(store -> assertThat(store.read("never-mined")).isEmpty());
    }

    @Test
    void createIsInsertOnlyAndDoesNotDiscardAccumulatedTruth() {
        withStore(store -> {
            store.create(CHAIN, "7d");
            store.advanceSourceReadOffset(CHAIN, "gtid:aaa-1:500");

            // a second seed must be refused: overwriting would discard the advanced offset. The
            // collision is a caller ordering error, surfaced bare like the unseeded-mutate error.
            assertThatThrownBy(() -> store.create(CHAIN, "30d")).isInstanceOf(IllegalStateException.class);

            assertThat(store.read(CHAIN).orElseThrow().sourceReadOffset()).isEqualTo("gtid:aaa-1:500");
        });
    }

    @Test
    void advanceSourceReadOffsetPersistsTheOpaqueToken() {
        withStore(store -> {
            store.create(CHAIN, null);

            store.advanceSourceReadOffset(CHAIN, "gtid:aaa-1:900");

            assertThat(store.read(CHAIN).orElseThrow().sourceReadOffset()).isEqualTo("gtid:aaa-1:900");
        });
    }

    @Test
    void upsertConsumerOffsetInsertsThenReplacesInPlaceByPipelineId() {
        withStore(store -> {
            store.create(CHAIN, null);

            store.upsertConsumerOffset(CHAIN, new ConsumerOffset("p1", Map.of("orders", 10L), null));
            store.upsertConsumerOffset(CHAIN, new ConsumerOffset("p2", Map.of("orders", 20L), null));
            store.upsertConsumerOffset(CHAIN, new ConsumerOffset("p1", Map.of("orders", 99L), "gtid:aaa-1:99"));

            List<ConsumerOffset> cursors = store.read(CHAIN).orElseThrow().consumerOffsets();
            assertThat(cursors).extracting(ConsumerOffset::pipelineId).containsExactlyInAnyOrder("p1", "p2");
            ConsumerOffset p1 = cursors.stream().filter(c -> c.pipelineId().equals("p1")).findFirst().orElseThrow();
            assertThat(p1.perTableSeq()).containsEntry("orders", 99L);
            assertThat(p1.sinkAckedSrcpos()).isEqualTo("gtid:aaa-1:99");
        });
    }

    @Test
    void advanceConsumerReadSeqAdvancesTheReadCursorWithoutClobberingTheSinkAckedPosition() {
        withStore(store -> {
            store.create(CHAIN, null);
            // The sink has acked a position for p1's cursor; the reader then advances its per-table read
            // cursor. The read cursor and the sink-ack are independent writers of one consumer record, so
            // the reader's advance must leave the acked position untouched.
            store.upsertConsumerOffset(CHAIN, new ConsumerOffset("p1", Map.of("orders", 5L), "gtid:aaa-1:100"));

            store.advanceConsumerReadSeq(CHAIN, "p1", "orders", 42L);

            ConsumerOffset p1 = onlyConsumer(store);
            assertThat(p1.perTableSeq()).containsEntry("orders", 42L);
            assertThat(p1.sinkAckedSrcpos()).isEqualTo("gtid:aaa-1:100");
        });
    }

    @Test
    void advanceConsumerReadSeqCreatesTheCursorWhenTheConsumerHasNoneYet() {
        withStore(store -> {
            store.create(CHAIN, null);
            // A reader may advance before the pipeline's sink first acks: the deep set creates the consumer
            // entry, and its acked position stays absent until a sink writes one.
            store.advanceConsumerReadSeq(CHAIN, "p1", "orders", 7L);

            ConsumerOffset p1 = onlyConsumer(store);
            assertThat(p1.perTableSeq()).containsEntry("orders", 7L);
            assertThat(p1.sinkAckedSrcpos()).isNull();
        });
    }

    @Test
    void advanceSinkAckedSrcposAdvancesTheAckedPositionWithoutClobberingTheReadCursor() {
        withStore(store -> {
            store.create(CHAIN, null);
            // The reader has advanced p1's per-table cursor; the sink then acks a durable position. The read
            // cursor and the sink-ack are independent writers of one consumer record, so the sink's advance
            // must leave the read cursor untouched.
            store.upsertConsumerOffset(CHAIN, new ConsumerOffset("p1", Map.of("orders", 42L), null));

            store.advanceSinkAckedSrcpos(CHAIN, "p1", "gtid:aaa-1:100");

            ConsumerOffset p1 = onlyConsumer(store);
            assertThat(p1.sinkAckedSrcpos()).isEqualTo("gtid:aaa-1:100");
            assertThat(p1.perTableSeq()).containsEntry("orders", 42L);
        });
    }

    @Test
    void advanceSinkAckedSrcposCreatesTheConsumerWhenItHasNoneYet() {
        withStore(store -> {
            store.create(CHAIN, null);
            // A sink may ack before the reader publishes any cursor: the deep set creates the consumer entry,
            // and its read cursor stays empty until a reader writes one.
            store.advanceSinkAckedSrcpos(CHAIN, "p1", "gtid:aaa-1:7");

            ConsumerOffset p1 = onlyConsumer(store);
            assertThat(p1.sinkAckedSrcpos()).isEqualTo("gtid:aaa-1:7");
            assertThat(p1.perTableSeq()).isEmpty();
        });
    }

    @Test
    void setCdcStartPositionPersistsTheSeamPosition() {
        withStore(store -> {
            store.create(CHAIN, null);

            store.setCdcStartPosition(CHAIN, "binlog.000042:1024");

            assertThat(store.read(CHAIN).orElseThrow().cdcStartPosition()).isEqualTo("binlog.000042:1024");
        });
    }

    @Test
    void appendSchemaVersionAppendsInOrder() {
        withStore(store -> {
            store.create(CHAIN, null);

            store.appendSchemaVersion(CHAIN, new SchemaVersion(0, Map.of("id", "int"), 0));
            store.appendSchemaVersion(CHAIN, new SchemaVersion(1, Map.of("id", "int", "name", "string"), 12));

            List<SchemaVersion> history = store.read(CHAIN).orElseThrow().schemaHistory();
            assertThat(history).extracting(SchemaVersion::version).containsExactly(0L, 1L);
            assertThat(history.get(1).ddlSeq()).isEqualTo(12L);
            assertThat(history.get(1).schema()).containsEntry("name", "string");
        });
    }

    @Test
    void mutateOnAnUnminedChainIsAnOrderingError() {
        // every mutator requires the chain to have been seeded by create first; a mutate on an unseeded
        // chain is a caller ordering error, not a silent no-op.
        withStore(store -> {
            assertThatThrownBy(() -> store.advanceSourceReadOffset("nope", "x"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.upsertConsumerOffset("nope", new ConsumerOffset("p", Map.of(), null)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.advanceConsumerReadSeq("nope", "p", "orders", 1L))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.advanceSinkAckedSrcpos("nope", "p", "gtid:aaa-1:1"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.setCdcStartPosition("nope", "x"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.appendSchemaVersion("nope", new SchemaVersion(0, Map.of(), 0)))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    /** The single consumer cursor on the test chain — the shape the per-consumer advance tests read back. */
    private static ConsumerOffset onlyConsumer(MongoSrsMetaStore store) {
        List<ConsumerOffset> cursors = store.read(CHAIN).orElseThrow().consumerOffsets();
        assertThat(cursors).hasSize(1);
        return cursors.get(0);
    }

    private interface StoreTest {
        void run(MongoSrsMetaStore store) throws Exception;
    }

    /** Runs a test body against a fresh meta store over a clean srs_meta collection on the replica-set. */
    private static void withStore(StoreTest test) {
        try (MongoClient client = MongoClients.create(REPLICA_SET.getReplicaSetUrl())) {
            MongoCollection<Document> collection = client.getDatabase("cyntex").getCollection("srs_meta");
            collection.drop();
            test.run(new MongoSrsMetaStore(collection));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
