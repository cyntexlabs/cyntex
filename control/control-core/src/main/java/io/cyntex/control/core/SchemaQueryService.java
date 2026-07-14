package io.cyntex.control.core;

import io.cyntex.spi.store.SchemaStore;

import java.util.Objects;
import java.util.Optional;

/**
 * The read side of schema discovery: the query peer of {@link SchemaDiscoveryService} (the audited write
 * that runs the probe and persists its envelope). It returns a connection's latest stored discovery as
 * the surface {@link SchemaReport}, or empty when the connection has never been discovered. A read of
 * derived observation data, not the config truth layer, so it carries no audit gate.
 *
 * <p>The store is the truth layer; this only projects the storage-port envelope onto the control-ring
 * report, so the faces render a control-ring type and never reach into the storage ports (the same split
 * the connection-test read side keeps between {@link ConnectionTestResultQueryService} and
 * {@link ConnectionTestService}).
 */
public final class SchemaQueryService {

    private final SchemaStore store;

    public SchemaQueryService(SchemaStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Returns the connection's latest discovered source model as the surface report, or empty when it has
     * never been discovered (which the HTTP face renders as a 404).
     */
    public Optional<SchemaReport> find(String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        return store.get(connectionId).map(SchemaReport::from);
    }
}
