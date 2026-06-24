package io.cyntex.core.catalog;

/**
 * Derives the {@link Discovery} axis from the connector group. poc1 cut: only file connectors have
 * no catalog; everything else can enumerate. A finer per-connector signal (a table-listing
 * capability versus group) is a known open question and would refine this later.
 */
public final class DiscoveryRules {

    private DiscoveryRules() {
    }

    public static Discovery fromGroup(ConnectorGroup group) {
        return group == ConnectorGroup.FILE ? Discovery.NONE : Discovery.CATALOG;
    }
}
