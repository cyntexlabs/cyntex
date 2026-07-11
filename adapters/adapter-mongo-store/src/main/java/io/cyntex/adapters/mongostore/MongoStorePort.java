package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBuckets;
import io.cyntex.spi.store.ArtifactStore;
import io.cyntex.spi.store.CatalogStore;
import io.cyntex.spi.store.ConnectionTestResultStore;
import io.cyntex.spi.store.ConnectorRegistry;
import io.cyntex.spi.store.SchemaStore;
import io.cyntex.spi.store.StateStore;
import io.cyntex.spi.store.StorePort;

import java.util.Objects;

/**
 * The MongoDB implementation of the persistence port: it aggregates the sub-stores — the artifact
 * truth layer, the epoch-fencing pipeline state store, the connection catalog, the discovered
 * source-schema store, the connector distribution registry, and the latest connection-test result per
 * connection — each bound to its own collection (or GridFS bucket) on the verified connection's
 * database. This is the store bridge the assembly root wires into the platform under {@code --role=all};
 * the app sees only the driver-free {@link StorePort}, so no driver type escapes this module (rule R3).
 */
public final class MongoStorePort implements StorePort {

    /** The collection holding the canonical artifact truth layer. */
    public static final String ARTIFACTS = "artifacts";
    /** The collection holding one epoch-fenced checkpoint per pipeline. */
    public static final String PIPELINE_STATE = "pipeline_state";
    /** The collection holding the registered connection configurations. */
    public static final String CONNECTIONS = "connections";
    /** The collection holding one discovered source model per connection. */
    public static final String SOURCE_SCHEMAS = "source_schemas";
    /** The GridFS bucket holding one registered connector artifact per content hash. */
    public static final String CONNECTOR_ARTIFACTS = "connector_artifacts";
    /** The collection holding the latest connection-test result per connection. */
    public static final String CONNECTION_TEST_RESULTS = "connection_test_results";

    private final ArtifactStore artifacts;
    private final StateStore state;
    private final CatalogStore catalog;
    private final SchemaStore schemas;
    private final ConnectorRegistry connectors;
    private final ConnectionTestResultStore connectionTestResults;

    /**
     * Binds the sub-stores to their own collections on the verified connection's database. The
     * connection must have been verified first (its client opened); the sub-stores share that one
     * client and are closed with it when the connection closes.
     */
    public MongoStorePort(MongoConnection connection) {
        Objects.requireNonNull(connection, "connection");
        MongoDatabase database = connection.database();
        this.artifacts = new MongoArtifactStore(connection.client(), database.getCollection(ARTIFACTS));
        this.state = new MongoStateStore(database.getCollection(PIPELINE_STATE));
        this.catalog = new MongoCatalogStore(database.getCollection(CONNECTIONS));
        this.schemas = new MongoSchemaStore(database.getCollection(SOURCE_SCHEMAS));
        this.connectors = new MongoConnectorRegistry(GridFSBuckets.create(database, CONNECTOR_ARTIFACTS));
        this.connectionTestResults =
                new MongoConnectionTestResultStore(database.getCollection(CONNECTION_TEST_RESULTS));
    }

    @Override
    public ArtifactStore artifacts() {
        return artifacts;
    }

    @Override
    public StateStore state() {
        return state;
    }

    @Override
    public CatalogStore catalog() {
        return catalog;
    }

    @Override
    public SchemaStore schemas() {
        return schemas;
    }

    @Override
    public ConnectorRegistry connectors() {
        return connectors;
    }

    @Override
    public ConnectionTestResultStore connectionTestResults() {
        return connectionTestResults;
    }
}
