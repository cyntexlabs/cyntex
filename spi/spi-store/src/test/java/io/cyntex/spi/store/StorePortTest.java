package io.cyntex.spi.store;

import static org.assertj.core.api.Assertions.assertThat;

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
    void facadeExposesTheThreeStores() {
        StorePort store = new InMemoryStore();

        assertThat(store.artifacts()).isNotNull();
        assertThat(store.state()).isNotNull();
        assertThat(store.catalog()).isNotNull();
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

    /**
     * An in-memory store: three maps behind the three sub-ports. The state store applies the real
     * fencing CAS, so the port composes with the core checkpoint contract, not a re-implementation.
     */
    private static final class InMemoryStore implements StorePort {

        private final Map<String, Resource> artifacts = new HashMap<>();
        private final Map<String, CheckpointDoc> checkpoints = new HashMap<>();
        private final Map<String, ConnectionConfig> connections = new HashMap<>();

        @Override
        public ArtifactStore artifacts() {
            return new ArtifactStore() {
                @Override
                public void save(Resource artifact) {
                    artifacts.put(artifact.id(), artifact);
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
    }
}
