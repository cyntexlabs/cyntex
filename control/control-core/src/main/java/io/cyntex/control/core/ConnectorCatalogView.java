package io.cyntex.control.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.spi.store.ConnectorCatalogStore;

/**
 * The online catalog view: the bundled snapshot overlaid with the rows derived for registered
 * connectors, read live from the store so a runtime registration is visible without a restart. It
 * backs the connector.list read verb and feeds the online apply path its capability matrix, while the
 * offline CLI keeps reading only the bundled snapshot (its honest boundary).
 */
public final class ConnectorCatalogView {

    private static final String BUNDLED = "bundled";
    private static final String REGISTERED = "registered";

    private final CyntexCatalog bundled;
    private final ConnectorCatalogStore store;

    public ConnectorCatalogView(CyntexCatalog bundled, ConnectorCatalogStore store) {
        this.bundled = Objects.requireNonNull(bundled, "bundled");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The live online catalog = the bundled snapshot with registered rows overlaid (registered shadows). */
    public CyntexCatalog merged() {
        return CyntexCatalog.merged(bundled, store.list());
    }

    /** Every connector visible online, tagged {@code bundled} or {@code registered}. */
    public List<ConnectorSummary> summaries() {
        List<ConnectorCatalogEntry> registered = store.list();
        Set<String> registeredIds = new HashSet<>();
        for (ConnectorCatalogEntry entry : registered) {
            registeredIds.add(entry.id());
        }
        List<ConnectorSummary> summaries = new ArrayList<>();
        for (ConnectorCatalogEntry entry : CyntexCatalog.merged(bundled, registered).all()) {
            summaries.add(ConnectorSummary.of(entry, registeredIds.contains(entry.id()) ? REGISTERED : BUNDLED));
        }
        return summaries;
    }
}
