package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoDatabase;
import io.cyntex.spi.store.ArtifactStore;
import io.cyntex.spi.store.CatalogStore;
import io.cyntex.spi.store.SrsMetaStore;
import io.cyntex.spi.store.StateStore;
import io.cyntex.spi.store.StorePort;

import java.util.Objects;

/**
 * The MongoDB implementation of the persistence port: it aggregates the four sub-stores — the
 * artifact truth layer, the epoch-fencing pipeline state store, the connection catalog, and the SRS
 * meta store (one durable coordination record per mining chain) — each bound to its own collection on
 * the verified connection's database. This is the store bridge the assembly root wires into the
 * platform under {@code --role=all}; the app sees only the driver-free {@link StorePort}, so no driver
 * type escapes this module (rule R3).
 */
public final class MongoStorePort implements StorePort {

    /** The collection holding the canonical artifact truth layer. */
    public static final String ARTIFACTS = "artifacts";
    /** The collection holding one epoch-fenced checkpoint per pipeline. */
    public static final String PIPELINE_STATE = "pipeline_state";
    /** The collection holding the registered connection configurations. */
    public static final String CONNECTIONS = "connections";
    /** The collection holding one SRS coordination record per mining chain. */
    public static final String SRS_META = "srs_meta";

    private final ArtifactStore artifacts;
    private final StateStore state;
    private final CatalogStore catalog;
    private final SrsMetaStore meta;

    /**
     * Binds the four sub-stores to their own collections on the verified connection's database. The
     * connection must have been verified first (its client opened); the sub-stores share that one
     * client and are closed with it when the connection closes.
     */
    public MongoStorePort(MongoConnection connection) {
        Objects.requireNonNull(connection, "connection");
        MongoDatabase database = connection.database();
        this.artifacts = new MongoArtifactStore(connection.client(), database.getCollection(ARTIFACTS));
        this.state = new MongoStateStore(database.getCollection(PIPELINE_STATE));
        this.catalog = new MongoCatalogStore(database.getCollection(CONNECTIONS));
        this.meta = new MongoSrsMetaStore(database.getCollection(SRS_META));
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
    public SrsMetaStore meta() {
        return meta;
    }
}
