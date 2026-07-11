package io.cyntex.app;

import io.cyntex.spi.store.ArtifactStore;
import io.cyntex.spi.store.CatalogStore;
import io.cyntex.spi.store.DesiredStore;
import io.cyntex.spi.store.StateStore;
import io.cyntex.spi.store.StorePort;

/**
 * In-memory {@link StorePort} for the convergence wiring test: it supplies real desired and state
 * sub-stores (what the converger consumes); the artifact and catalog sub-stores are not exercised here.
 */
final class InMemoryStorePort implements StorePort {

    private final InMemoryDesiredStore desired = new InMemoryDesiredStore();
    private final InMemoryStateStore state = new InMemoryStateStore();

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
    public CatalogStore catalog() {
        throw new UnsupportedOperationException("catalog is not exercised by the convergence wiring test");
    }
}
