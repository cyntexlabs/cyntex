package io.cyntex.core.catalog;

/**
 * The organising group a connector belongs to, used to list connectors in the wizard. Derived from
 * the connector's tags and refined against its resolved modes (tags are unreliable — e.g. Kafka
 * tags itself {@code Database}). {@code OTHER} is the honest fallback when nothing classifies it.
 */
public enum ConnectorGroup {
    DATABASE("database"),
    SAAS("saas"),
    FILE("file"),
    MQ("mq"),
    OTHER("other");

    private final String yaml;

    ConnectorGroup(String yaml) {
        this.yaml = yaml;
    }

    public String yaml() {
        return yaml;
    }
}
