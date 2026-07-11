package io.cyntex.spi.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.lifecycle.CasOutcome;
import io.cyntex.core.lifecycle.CheckpointDoc;
import io.cyntex.core.lifecycle.EpochCas;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.ViewResource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The store port seen through an in-memory implementation: proves the persistence contract is
 * implementable and usable, and pins the shape it documents — an artifact truth layer
 * (save / get / list of canonical resources), a state store whose only write path is the
 * epoch-fencing compare-and-swap, a connection catalog store, a discovered source-schema store, and a
 * connector distribution registry.
 */
class StorePortTest {

    private static final Instant T0 = Instant.parse("2026-07-03T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-03T00:00:01Z");

    private static SourceResource source(String id, String connector) {
        return new SourceResource(id, null, connector, Map.of(), null, null, null, null, null);
    }

    // --- facade ---

    @Test
    void facadeExposesTheSixStores() {
        StorePort store = new InMemoryStore();

        assertThat(store.artifacts()).isNotNull();
        assertThat(store.state()).isNotNull();
        assertThat(store.catalog()).isNotNull();
        assertThat(store.schemas()).isNotNull();
        assertThat(store.connectors()).isNotNull();
        assertThat(store.connectionTestResults()).isNotNull();
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

    // --- schemas (discovered source models) ---

    @Test
    void schemaSaveThenGetRoundTrips() {
        SchemaStore schemas = new InMemoryStore().schemas();
        SourceModel model = new SourceModel(List.of(
                new SourceTable("orders", List.of(new SourceField("id", "bigint")), List.of("id"), List.of())));

        schemas.save("orders-db", model);

        assertThat(schemas.get("orders-db")).contains(model);
    }

    @Test
    void schemaGetAbsentConnectionIsEmpty() {
        assertThat(new InMemoryStore().schemas().get("never-discovered")).isEmpty();
    }

    @Test
    void reDiscoveryReplacesTheStoredModelInPlace() {
        SchemaStore schemas = new InMemoryStore().schemas();
        schemas.save("orders-db", new SourceModel(List.of(
                new SourceTable("orders", List.of(), List.of(), List.of()))));
        SourceModel rediscovered = new SourceModel(List.of(
                new SourceTable("orders", List.of(new SourceField("id", "bigint")), List.of("id"), List.of()),
                new SourceTable("customers", List.of(), List.of(), List.of())));

        schemas.save("orders-db", rediscovered);

        assertThat(schemas.get("orders-db")).contains(rediscovered);
    }

    // --- connectors (the distribution registry) ---

    @Test
    void connectorRegisterIsContentHashIdempotent() {
        ConnectorRegistry connectors = new InMemoryStore().connectors();
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);

        RegistrationOutcome first = connectors.register("mysql", "1.3.5", RegistrationSource.REGISTER, jar);
        RegistrationOutcome again = connectors.register("mysql", "1.3.5", RegistrationSource.REGISTER, jar);

        assertThat(first.newlyRegistered()).isTrue();
        assertThat(again.newlyRegistered()).isFalse();
        assertThat(again.registration()).isEqualTo(first.registration());
    }

    @Test
    void connectorRegisterStoresRetrievableArtifactBytes() {
        ConnectorRegistry connectors = new InMemoryStore().connectors();
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);

        RegistrationOutcome outcome = connectors.register("mysql", "1.3.5", RegistrationSource.SEED, jar);

        assertThat(connectors.artifact(outcome.registration().contentHash()).orElseThrow()).isEqualTo(jar);
    }

    @Test
    void connectorListReturnsEveryRegistered() {
        ConnectorRegistry connectors = new InMemoryStore().connectors();
        connectors.register("mysql", "1.3.5", RegistrationSource.SEED, "a".getBytes(StandardCharsets.UTF_8));
        connectors.register("postgres", "1.3.5", RegistrationSource.REGISTER, "b".getBytes(StandardCharsets.UTF_8));

        assertThat(connectors.list())
                .extracting(ConnectorRegistration::connectorId)
                .containsExactlyInAnyOrder("mysql", "postgres");
    }

    // --- connection test results (latest-only per connection) ---

    private static ConnectionTestResult passed(String connectionId, String connectorId) {
        return new ConnectionTestResult(
                connectionId,
                connectorId,
                ConnectionTestResult.Outcome.PASSED,
                List.of(new ConnectionTestItem("Connection", ConnectionTestItem.Status.PASSED, null, null, null, null)),
                1783939200000L);
    }

    @Test
    void connectionTestResultSaveThenFindRoundTrips() {
        ConnectionTestResultStore results = new InMemoryStore().connectionTestResults();
        ConnectionTestResult result = passed("orders-db", "mysql");

        results.save(result);

        assertThat(results.find("orders-db")).contains(result);
    }

    @Test
    void connectionTestResultFindAbsentConnectionIsEmpty() {
        assertThat(new InMemoryStore().connectionTestResults().find("never-tested")).isEmpty();
    }

    @Test
    void reTestReplacesTheStoredResultInPlace() {
        ConnectionTestResultStore results = new InMemoryStore().connectionTestResults();
        results.save(passed("orders-db", "mysql"));
        ConnectionTestResult reTested = new ConnectionTestResult(
                "orders-db",
                "mysql",
                ConnectionTestResult.Outcome.FAILED,
                List.of(new ConnectionTestItem(
                        "Login", ConnectionTestItem.Status.FAILED, "auth failed", null, null, "11000")),
                1783939300000L);

        results.save(reTested);

        assertThat(results.find("orders-db")).contains(reTested);
    }

    /**
     * An in-memory store: one map behind each sub-port. The state store applies the real fencing CAS,
     * so the port composes with the core checkpoint contract, not a re-implementation. The registry
     * keys artifacts by a deterministic content key (raw-byte hex here, SHA-256 in the real store), so
     * it witnesses the register-if-absent contract without depending on the hash algorithm.
     */
    private static final class InMemoryStore implements StorePort {

        private final Map<String, Resource> artifacts = new HashMap<>();
        private final Map<String, CheckpointDoc> checkpoints = new HashMap<>();
        private final Map<String, ConnectionConfig> connections = new HashMap<>();
        private final Map<String, SourceModel> schemas = new HashMap<>();
        private final Map<String, ConnectorRegistration> registrations = new HashMap<>();
        private final Map<String, byte[]> connectorArtifacts = new HashMap<>();
        private final Map<String, ConnectionTestResult> testResults = new HashMap<>();

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
        public SchemaStore schemas() {
            return new SchemaStore() {
                @Override
                public void save(String connectionId, SourceModel model) {
                    schemas.put(connectionId, model);
                }

                @Override
                public Optional<SourceModel> get(String connectionId) {
                    return Optional.ofNullable(schemas.get(connectionId));
                }
            };
        }

        @Override
        public ConnectorRegistry connectors() {
            return new ConnectorRegistry() {
                @Override
                public RegistrationOutcome register(
                        String connectorId, String pdkApiVersion, RegistrationSource source, byte[] artifact) {
                    String contentHash = HexFormat.of().formatHex(artifact);
                    ConnectorRegistration existing = registrations.get(contentHash);
                    if (existing != null) {
                        return new RegistrationOutcome(existing, false);
                    }
                    ConnectorRegistration registration =
                            new ConnectorRegistration(connectorId, contentHash, pdkApiVersion, source);
                    registrations.put(contentHash, registration);
                    connectorArtifacts.put(contentHash, artifact.clone());
                    return new RegistrationOutcome(registration, true);
                }

                @Override
                public List<ConnectorRegistration> list() {
                    return new ArrayList<>(registrations.values());
                }

                @Override
                public Optional<byte[]> artifact(String contentHash) {
                    byte[] bytes = connectorArtifacts.get(contentHash);
                    return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
                }
            };
        }

        @Override
        public ConnectionTestResultStore connectionTestResults() {
            return new ConnectionTestResultStore() {
                @Override
                public void save(ConnectionTestResult result) {
                    testResults.put(result.connectionId(), result);
                }

                @Override
                public Optional<ConnectionTestResult> find(String connectionId) {
                    return Optional.ofNullable(testResults.get(connectionId));
                }
            };
        }
    }
}
