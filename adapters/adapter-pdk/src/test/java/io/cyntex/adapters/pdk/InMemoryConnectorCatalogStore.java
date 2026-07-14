package io.cyntex.adapters.pdk;

import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.spi.store.ConnectorCatalogStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An in-memory connector catalog row store for tests: one row per connector id, latest-only. */
final class InMemoryConnectorCatalogStore implements ConnectorCatalogStore {

    private final Map<String, ConnectorCatalogEntry> rows = new LinkedHashMap<>();

    @Override
    public void upsert(ConnectorCatalogEntry entry) {
        rows.put(entry.id(), entry);
    }

    @Override
    public Optional<ConnectorCatalogEntry> get(String connectorId) {
        return Optional.ofNullable(rows.get(connectorId));
    }

    @Override
    public List<ConnectorCatalogEntry> list() {
        return new ArrayList<>(rows.values());
    }
}
