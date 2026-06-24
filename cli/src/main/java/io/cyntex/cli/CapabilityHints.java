package io.cyntex.cli;

import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.catalog.ConnectorGroup;
import io.cyntex.core.catalog.ModeSource;
import io.cyntex.core.model.SourceMode;

/**
 * Catalog-derived capability hints the wizard mirrors from the offline validate rules, so the
 * wizard accepts (non-interactive) and offers (interactive) exactly what {@code validate} would.
 *
 * <p>The mode capability matrix is authoritative only when the catalog entry is <em>trustworthy</em>
 * — a DATABASE connector, or one carrying at least one declared mode. Otherwise the derived modes
 * are advisory and the wizard defers to the server (accepts any mode), matching the capability layer.
 */
final class CapabilityHints {

    private CapabilityHints() {
    }

    /** Whether the entry's mode list is a hard constraint (else it is advisory, defer to server). */
    static boolean modesAreTrustworthy(ConnectorCatalogEntry entry) {
        return entry.group() == ConnectorGroup.DATABASE
                || entry.provenance().modeSource().containsValue(ModeSource.DECLARED);
    }

    /** Whether {@code mode} is acceptable for {@code entry} offline (mirrors the capability layer). */
    static boolean isModeAllowed(ConnectorCatalogEntry entry, SourceMode mode) {
        if (entry.modes().isEmpty() || !modesAreTrustworthy(entry)) {
            return true; // no trustworthy offline signal — defer to the server
        }
        return entry.modes().contains(mode);
    }
}
