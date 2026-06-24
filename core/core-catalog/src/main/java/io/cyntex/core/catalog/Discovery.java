package io.cyntex.core.catalog;

/**
 * Whether a connector can enumerate its tables/streams for the wizard. {@code CATALOG} means the
 * source has a table catalog (so {@code tables} may be omitted or matched by regex); {@code NONE}
 * means it has no catalog (bare files), so the user must name tables literally.
 */
public enum Discovery {
    CATALOG("catalog"),
    NONE("none");

    private final String yaml;

    Discovery(String yaml) {
        this.yaml = yaml;
    }

    public String yaml() {
        return yaml;
    }
}
