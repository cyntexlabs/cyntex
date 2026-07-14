package io.cyntex.app;

import io.cyntex.spi.store.ArtifactStore;
import io.cyntex.spi.store.CatalogStore;
import io.cyntex.spi.store.ConnectionTestResultStore;
import io.cyntex.spi.store.ConnectorCatalogStore;
import io.cyntex.spi.store.ConnectorRegistry;
import io.cyntex.spi.store.DesiredStore;
import io.cyntex.spi.store.ObservationStore;
import io.cyntex.spi.store.SchemaStore;
import io.cyntex.spi.store.SrsMetaStore;
import io.cyntex.spi.store.StateStore;
import io.cyntex.spi.store.StorePort;

/**
 * In-memory {@link StorePort} for the convergence wiring test: it supplies real desired, state and
 * observation sub-stores (what the converger consumes and the publisher writes); the artifact and
 * catalog sub-stores are not exercised here.
 */
final class InMemoryStorePort implements StorePort {

    private final InMemoryDesiredStore desired = new InMemoryDesiredStore();
    private final InMemoryStateStore state = new InMemoryStateStore();
    private final InMemoryObservationStore observations = new InMemoryObservationStore();

    @Override
    public ArtifactStore artifacts() {
        throw new UnsupportedOperationException("artifacts are not exercised by the convergence wiring test");
    }

    @Override
    public StateStore state() {
        return state;
    }

    @Override
    public DesiredStore desired() {
        return desired;
    }

    @Override
    public ObservationStore observations() {
        return observations;
    }

    @Override
    public CatalogStore catalog() {
        throw new UnsupportedOperationException("catalog is not exercised by the convergence wiring test");
    }

    @Override
    public SchemaStore schemas() {
        throw new UnsupportedOperationException("schemas are not exercised by the convergence wiring test");
    }

    @Override
    public ConnectorRegistry connectors() {
        throw new UnsupportedOperationException("connectors are not exercised by the convergence wiring test");
    }

    @Override
    public ConnectorCatalogStore connectorCatalog() {
        throw new UnsupportedOperationException("the connector catalog is not exercised by the convergence wiring test");
    }

    @Override
    public ConnectionTestResultStore connectionTestResults() {
        throw new UnsupportedOperationException(
                "connection test results are not exercised by the convergence wiring test");
    }

    @Override
    public SrsMetaStore meta() {
        throw new UnsupportedOperationException("the SRS meta store is not exercised by the convergence wiring test");
    }
}
