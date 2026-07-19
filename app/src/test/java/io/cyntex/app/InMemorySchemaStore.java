package io.cyntex.app;

import io.cyntex.spi.store.DiscoveredSourceModel;
import io.cyntex.spi.store.SchemaStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link SchemaStore} for the assembly-layer tests: a latest-only upsert keyed by connection id.
 * Enough to seed a source's discovered model so the target-model resolution has a model to read, without a
 * store backend.
 */
final class InMemorySchemaStore implements SchemaStore {

    private final Map<String, DiscoveredSourceModel> byConnection = new LinkedHashMap<>();

    @Override
    public void save(DiscoveredSourceModel discovered) {
        byConnection.put(discovered.connectionId(), discovered);
    }

    @Override
    public Optional<DiscoveredSourceModel> get(String connectionId) {
        return Optional.ofNullable(byConnection.get(connectionId));
    }
}
