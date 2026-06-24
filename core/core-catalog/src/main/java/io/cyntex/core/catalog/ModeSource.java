package io.cyntex.core.catalog;

/**
 * Where a connector's mode came from: a default derived from its registered capabilities, or an
 * explicit declaration in the connector's spec. Recorded per mode so the catalog can be audited and
 * the ingest report can show which modes are heuristic.
 */
public enum ModeSource {
    DERIVED("derived"),
    DECLARED("declared");

    private final String yaml;

    ModeSource(String yaml) {
        this.yaml = yaml;
    }

    public String yaml() {
        return yaml;
    }
}
