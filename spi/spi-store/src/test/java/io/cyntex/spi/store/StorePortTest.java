package io.cyntex.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.lifecycle.CasOutcome;
import io.cyntex.core.lifecycle.CheckpointDoc;
import io.cyntex.core.lifecycle.EpochCas;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.ViewResource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The store port seen through an in-memory implementation: proves the three-store persistence
 * contract is implementable and usable, and pins the shape it documents — an artifact truth layer
 * (save / get / list of canonical resources), a state store whose only write path is the
 * epoch-fencing compare-and-swap, and a connection catalog store.
 */
class StorePortTest {

    private static final Instant T0 = Instant.parse("2026-07-03T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-03T00:00:01Z");

    private static SourceResource source(String id, String connector) {
        return new SourceResource(id, null, connector, Map.of(), null, null, null, null, null);
    }

    // --- facade ---

    @Test
    void facadeExposesTheFourStores() {
        StorePort store = new InMemoryStore();

        assertThat(store.artifacts()).isNotNull();
        assertThat(store.state()).isNotNull();
        assertThat(store.catalog()).isNotNull();
        assertThat(store.meta()).isNotNull();
    }

    // --- artifacts (the canonical truth layer) ---

    @Test
    void artifactSaveThenGetRoundTrips() {
        ArtifactStore artifacts = new InMemoryStore().artifacts();
        Resource orders = source("orders", "mysql");

        artifacts.save(orders);

        assertThat(artifacts.get("orders")).contains(orders);
    }

    @Test
    void artifactGetAbsentIsEmpty() {
        assertThat(new InMemoryStore().artifacts().get("missing")).isEmpty();
    }

    @Test
    void artifactSaveUpsertsById() {
        ArtifactStore artifacts = new InMemoryStore().artifacts();
        artifacts.save(source("orders", "mysql"));
        artifacts.save(source("orders", "postgres"));

        assertThat(((SourceResource) artifacts.get("orders").orElseThrow()).connector()).isEqualTo("postgres");
        assertThat(artifacts.list()).hasSize(1);
    }

    @Test
    void artifactListReturnsEverySaved() {
        ArtifactStore artifacts = new InMemoryStore().artifacts();
        artifacts.save(source("orders", "mysql"));
        artifacts.save(new ViewResource("mdm", null, null, null, null, null));

        assertThat(artifacts.list()).extracting(Resource::id).containsExactlyInAnyOrder("orders", "mdm");
    }

    @Test
    void artifactSaveAllUpsertsEveryResourceById() {
        ArtifactStore artifacts = new InMemoryStore().artifacts();

        artifacts.saveAll(List.of(source("orders", "mysql"), new ViewResource("mdm", null, null, null, null, null)));

        assertThat(artifacts.list()).extracting(Resource::id).containsExactlyInAnyOrder("orders", "mdm");
        // A second batch upserts by id in place rather than accumulating documents.
        artifacts.saveAll(List.of(source("orders", "postgres")));
        assertThat(((SourceResource) artifacts.get("orders").orElseThrow()).connector()).isEqualTo("postgres");
        assertThat(artifacts.list()).hasSize(2);
    }

    @Test
    void artifactSaveAllOfAnEmptyBatchWritesNothing() {
        ArtifactStore artifacts = new InMemoryStore().artifacts();

        artifacts.saveAll(List.of());

        assertThat(artifacts.list()).isEmpty();
    }

    // --- state (the epoch-fencing compare-and-swap) ---

    @Test
    void stateCreateThenReadRoundTrips() {
        StateStore state = new InMemoryStore().state();

        state.create("p1", "NEW", T0);

        assertThat(state.read("p1")).contains(CheckpointDoc.initial("p1", "NEW", T0));
    }

    @Test
    void stateReadAbsentIsEmpty() {
        assertThat(new InMemoryStore().state().read("p1")).isEmpty();
    }

    @Test
    void stateCompareAndSwapAppliesAndBumpsEpochOnMatchingEpoch() {
        StateStore state = new InMemoryStore().state();
        state.create("p1", "NEW", T0);

        CasOutcome outcome = state.compareAndSwap("p1", 0, "RUNNING", T1);

        assertThat(outcome).isInstanceOf(CasOutcome.Applied.class);
        CheckpointDoc next = ((CasOutcome.Applied) outcome).next();
        assertThat(next.epoch()).isEqualTo(1);
        assertThat(next.stateJson()).isEqualTo("RUNNING");
        assertThat(state.read("p1")).contains(next);
    }

    @Test
    void stateCompareAndSwapIsFencedByAStaleEpoch() {
        StateStore state = new InMemoryStore().state();
        state.create("p1", "NEW", T0);
        state.compareAndSwap("p1", 0, "RUNNING", T1); // the epoch is now 1

        CasOutcome outcome = state.compareAndSwap("p1", 0, "PAUSED", T1); // a stale writer still at epoch 0

        assertThat(outcome).isInstanceOf(CasOutcome.Fenced.class);
        assertThat(((CasOutcome.Fenced) outcome).currentEpoch()).isEqualTo(1);
        assertThat(state.read("p1").orElseThrow().stateJson()).isEqualTo("RUNNING"); // the loser never overwrote
    }

    @Test
    void stateCreateIsInsertOnlyAndDoesNotResetTheEpoch() {
        StateStore state = new InMemoryStore().state();
        state.create("p1", "NEW", T0);
        state.compareAndSwap("p1", 0, "RUNNING", T1); // the epoch is now 1

        state.create("p1", "NEW", T0); // a second seed must not overwrite the advanced checkpoint

        CheckpointDoc stored = state.read("p1").orElseThrow();
        assertThat(stored.epoch()).isEqualTo(1); // the fence was not reset
        assertThat(stored.stateJson()).isEqualTo("RUNNING");
    }

    // --- catalog (connection / connector-instance config) ---

    @Test
    void catalogSaveThenGetRoundTrips() {
        CatalogStore catalog = new InMemoryStore().catalog();
        ConnectionConfig conn = new ConnectionConfig("orders-db", "mysql", Map.of("host", "db"));

        catalog.save(conn);

        assertThat(catalog.get("orders-db")).contains(conn);
    }

    @Test
    void catalogGetAbsentIsEmpty() {
        assertThat(new InMemoryStore().catalog().get("missing")).isEmpty();
    }

    @Test
    void catalogListReturnsEverySaved() {
        CatalogStore catalog = new InMemoryStore().catalog();
        catalog.save(new ConnectionConfig("orders-db", "mysql", Map.of()));
        catalog.save(new ConnectionConfig("events", "kafka", Map.of()));

        assertThat(catalog.list()).extracting(ConnectionConfig::id).containsExactlyInAnyOrder("orders-db", "events");
    }

    // --- meta (the SRS mining-chain coordination store) ---

    @Test
    void metaCreateThenReadRoundTrips() {
        SrsMetaStore meta = new InMemoryStore().meta();

        meta.create("orders@mysql-1", "7d");

        SrsMeta seeded = meta.read("orders@mysql-1").orElseThrow();
        assertThat(seeded.miningChainId()).isEqualTo("orders@mysql-1");
        assertThat(seeded.retention()).isEqualTo("7d");
        assertThat(seeded.sourceReadOffset()).isNull();
        assertThat(seeded.cdcStartPosition()).isNull();
        assertThat(seeded.consumerOffsets()).isEmpty();
        assertThat(seeded.schemaHistory()).isEmpty();
    }

    @Test
    void metaReadAbsentIsEmpty() {
        assertThat(new InMemoryStore().meta().read("never-mined")).isEmpty();
    }

    @Test
    void metaCreateIsInsertOnlyAndDoesNotDiscardAccumulatedTruth() {
        SrsMetaStore meta = new InMemoryStore().meta();
        meta.create("chain", "7d");
        meta.advanceSourceReadOffset("chain", "gtid:aaa-1:500");

        meta.create("chain", "30d"); // a second seed must not wipe the advanced offset

        assertThat(meta.read("chain").orElseThrow().sourceReadOffset()).isEqualTo("gtid:aaa-1:500");
    }

    @Test
    void metaAdvanceSourceReadOffsetPersistsTheOpaqueToken() {
        SrsMetaStore meta = new InMemoryStore().meta();
        meta.create("chain", null);

        meta.advanceSourceReadOffset("chain", "gtid:aaa-1:900");

        assertThat(meta.read("chain").orElseThrow().sourceReadOffset()).isEqualTo("gtid:aaa-1:900");
    }

    @Test
    void metaUpsertConsumerOffsetInsertsThenReplacesByPipelineId() {
        SrsMetaStore meta = new InMemoryStore().meta();
        meta.create("chain", null);

        meta.upsertConsumerOffset("chain", new ConsumerOffset("p1", Map.of("orders", 10L), null));
        meta.upsertConsumerOffset("chain", new ConsumerOffset("p2", Map.of("orders", 20L), null));
        meta.upsertConsumerOffset("chain", new ConsumerOffset("p1", Map.of("orders", 99L), "gtid:aaa-1:99"));

        List<ConsumerOffset> cursors = meta.read("chain").orElseThrow().consumerOffsets();
        assertThat(cursors).extracting(ConsumerOffset::pipelineId).containsExactly("p1", "p2");
        ConsumerOffset p1 = cursors.stream().filter(c -> c.pipelineId().equals("p1")).findFirst().orElseThrow();
        assertThat(p1.perTableSeq()).containsEntry("orders", 99L);
        assertThat(p1.sinkAckedSrcpos()).isEqualTo("gtid:aaa-1:99");
    }

    @Test
    void metaSetCdcStartPositionPersistsTheSeamPosition() {
        SrsMetaStore meta = new InMemoryStore().meta();
        meta.create("chain", null);

        meta.setCdcStartPosition("chain", "binlog.000042:1024");

        assertThat(meta.read("chain").orElseThrow().cdcStartPosition()).isEqualTo("binlog.000042:1024");
    }

    @Test
    void metaAppendSchemaVersionIsAppendOnly() {
        SrsMetaStore meta = new InMemoryStore().meta();
        meta.create("chain", null);

        meta.appendSchemaVersion("chain", new SchemaVersion(0, Map.of("id", "int"), 0));
        meta.appendSchemaVersion("chain", new SchemaVersion(1, Map.of("id", "int", "name", "string"), 12));

        List<SchemaVersion> history = meta.read("chain").orElseThrow().schemaHistory();
        assertThat(history).extracting(SchemaVersion::version).containsExactly(0L, 1L);
        assertThat(history.get(1).ddlSeq()).isEqualTo(12L);
    }

    @Test
    void metaMutateOnAnUnseededChainIsAnOrderingError() {
        SrsMetaStore meta = new InMemoryStore().meta();
        // every mutator requires the chain to have been seeded by create first; a mutate on an unseeded
        // chain is a caller ordering error, surfaced bare.
        assertThatThrownBy(() -> meta.advanceSourceReadOffset("nope", "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> meta.upsertConsumerOffset("nope", new ConsumerOffset("p", Map.of(), null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> meta.setCdcStartPosition("nope", "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> meta.appendSchemaVersion("nope", new SchemaVersion(0, Map.of(), 0)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * An in-memory store: four maps behind the four sub-ports. The state store applies the real
     * fencing CAS, so the port composes with the core checkpoint contract, not a re-implementation.
     */
    private static final class InMemoryStore implements StorePort {

        private final Map<String, Resource> artifacts = new HashMap<>();
        private final Map<String, CheckpointDoc> checkpoints = new HashMap<>();
        private final Map<String, ConnectionConfig> connections = new HashMap<>();
        private final Map<String, SrsMeta> srsMeta = new HashMap<>();

        @Override
        public ArtifactStore artifacts() {
            return new ArtifactStore() {
                @Override
                public void saveAll(List<Resource> batch) {
                    // atomic in spirit: the map puts cannot fail partway, so either the batch is applied
                    // in full or (never, here) not at all.
                    for (Resource artifact : batch) {
                        artifacts.put(artifact.id(), artifact);
                    }
                }

                @Override
                public Optional<Resource> get(String id) {
                    return Optional.ofNullable(artifacts.get(id));
                }

                @Override
                public List<Resource> list() {
                    return new ArrayList<>(artifacts.values());
                }
            };
        }

        @Override
        public StateStore state() {
            return new StateStore() {
                @Override
                public Optional<CheckpointDoc> read(String pipelineId) {
                    return Optional.ofNullable(checkpoints.get(pipelineId));
                }

                @Override
                public void create(String pipelineId, String stateJson, Instant touchTime) {
                    // insert-only: a second seed must never overwrite and reset the fencing epoch
                    checkpoints.putIfAbsent(pipelineId, CheckpointDoc.initial(pipelineId, stateJson, touchTime));
                }

                @Override
                public CasOutcome compareAndSwap(
                        String pipelineId, long expectedEpoch, String nextStateJson, Instant touchTime) {
                    CasOutcome outcome =
                            EpochCas.swap(checkpoints.get(pipelineId), expectedEpoch, nextStateJson, touchTime);
                    if (outcome instanceof CasOutcome.Applied applied) {
                        checkpoints.put(pipelineId, applied.next());
                    }
                    return outcome;
                }
            };
        }

        @Override
        public CatalogStore catalog() {
            return new CatalogStore() {
                @Override
                public void save(ConnectionConfig connection) {
                    connections.put(connection.id(), connection);
                }

                @Override
                public Optional<ConnectionConfig> get(String id) {
                    return Optional.ofNullable(connections.get(id));
                }

                @Override
                public List<ConnectionConfig> list() {
                    return new ArrayList<>(connections.values());
                }
            };
        }

        @Override
        public SrsMetaStore meta() {
            return new SrsMetaStore() {
                @Override
                public Optional<SrsMeta> read(String miningChainId) {
                    return Optional.ofNullable(srsMeta.get(miningChainId));
                }

                @Override
                public void create(String miningChainId, String retention) {
                    // insert-only: a second seed must never discard the accumulated offset / cursor /
                    // schema truth the chain has built up.
                    srsMeta.putIfAbsent(miningChainId,
                            new SrsMeta(miningChainId, null, List.of(), null, List.of(), retention));
                }

                @Override
                public void advanceSourceReadOffset(String miningChainId, String sourceReadOffset) {
                    SrsMeta current = require(miningChainId);
                    srsMeta.put(miningChainId, new SrsMeta(current.miningChainId(), sourceReadOffset,
                            current.consumerOffsets(), current.cdcStartPosition(), current.schemaHistory(),
                            current.retention()));
                }

                @Override
                public void upsertConsumerOffset(String miningChainId, ConsumerOffset offset) {
                    SrsMeta current = require(miningChainId);
                    List<ConsumerOffset> merged = new ArrayList<>();
                    boolean replaced = false;
                    for (ConsumerOffset existing : current.consumerOffsets()) {
                        if (existing.pipelineId().equals(offset.pipelineId())) {
                            merged.add(offset);
                            replaced = true;
                        } else {
                            merged.add(existing);
                        }
                    }
                    if (!replaced) {
                        merged.add(offset);
                    }
                    srsMeta.put(miningChainId, new SrsMeta(current.miningChainId(), current.sourceReadOffset(),
                            merged, current.cdcStartPosition(), current.schemaHistory(), current.retention()));
                }

                @Override
                public void advanceConsumerReadSeq(String miningChainId, String pipelineId, String table, long lastReadSeq) {
                    SrsMeta current = require(miningChainId);
                    List<ConsumerOffset> merged = new ArrayList<>();
                    boolean advanced = false;
                    for (ConsumerOffset existing : current.consumerOffsets()) {
                        if (existing.pipelineId().equals(pipelineId)) {
                            Map<String, Long> perTable = new HashMap<>(existing.perTableSeq());
                            perTable.put(table, lastReadSeq);
                            // Advance the read cursor only; the consumer's sink-acked position is untouched.
                            merged.add(new ConsumerOffset(pipelineId, perTable, existing.sinkAckedSrcpos()));
                            advanced = true;
                        } else {
                            merged.add(existing);
                        }
                    }
                    if (!advanced) {
                        // A reader may advance before the sink first acks: create the entry, acked absent.
                        merged.add(new ConsumerOffset(pipelineId, Map.of(table, lastReadSeq), null));
                    }
                    srsMeta.put(miningChainId, new SrsMeta(current.miningChainId(), current.sourceReadOffset(),
                            merged, current.cdcStartPosition(), current.schemaHistory(), current.retention()));
                }

                @Override
                public void setCdcStartPosition(String miningChainId, String cdcStartPosition) {
                    SrsMeta current = require(miningChainId);
                    srsMeta.put(miningChainId, new SrsMeta(current.miningChainId(), current.sourceReadOffset(),
                            current.consumerOffsets(), cdcStartPosition, current.schemaHistory(),
                            current.retention()));
                }

                @Override
                public void appendSchemaVersion(String miningChainId, SchemaVersion version) {
                    SrsMeta current = require(miningChainId);
                    List<SchemaVersion> history = new ArrayList<>(current.schemaHistory());
                    history.add(version);
                    srsMeta.put(miningChainId, new SrsMeta(current.miningChainId(), current.sourceReadOffset(),
                            current.consumerOffsets(), current.cdcStartPosition(), history, current.retention()));
                }

                private SrsMeta require(String miningChainId) {
                    SrsMeta current = srsMeta.get(miningChainId);
                    if (current == null) {
                        throw new IllegalStateException("srs meta mutate on an unseeded mining chain: "
                                + miningChainId + " (create must seed it first)");
                    }
                    return current;
                }
            };
        }
    }
}
