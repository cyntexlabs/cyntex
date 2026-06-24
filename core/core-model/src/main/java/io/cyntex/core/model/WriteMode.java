package io.cyntex.core.model;

/**
 * Sink write mode (ADR-0016 §8, X6): each value is one i/u/d disposal preset. Default is
 * {@code upsert}; key dependency (upsert needs a primary key) is a validate-layer rule.
 */
@Doc("How a sink applies inserts, updates and deletes to the target.")
public enum WriteMode {
    @Doc("Insert new rows and update existing ones by primary key; deletes remove the matching row.")
    UPSERT("upsert"),
    @Doc("Always insert rows without matching on a key; updates and deletes are not applied.")
    APPEND("append");

    private final String yaml;

    WriteMode(String yaml) {
        this.yaml = yaml;
    }

    public String yaml() {
        return yaml;
    }
}
