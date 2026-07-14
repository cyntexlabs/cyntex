package io.cyntex.spi.store;

import java.util.List;
import java.util.Optional;

import io.cyntex.core.catalog.ConnectorCatalogEntry;

/**
 * The derived catalog rows for server-registered connectors: one normalized
 * {@link ConnectorCatalogEntry} per connector id, produced when a connector is registered and read
 * back to form the online catalog view. Distinct from {@link ConnectorRegistry}, which is the
 * content-addressed artifact-bytes truth — this is the structured capability row keyed by connector
 * id, latest-only, so a re-register overwrites the row in place rather than accumulating versions. A
 * pure interface (rule R2); it carries no connector-framework or store-driver types.
 */
public interface ConnectorCatalogStore {

    /** Upserts the derived row for its connector id; latest-only, a re-register overwrites in place. */
    void upsert(ConnectorCatalogEntry entry);

    /** Returns the stored derived row for a connector id, or empty if none has been derived. */
    Optional<ConnectorCatalogEntry> get(String connectorId);

    /** Every stored derived connector row. */
    List<ConnectorCatalogEntry> list();
}
