package io.cyntex.spi.store;

import java.util.Objects;

/**
 * The stored unit of schema discovery for one connection: the normalized {@link SourceModel} wrapped
 * with the identity and freshness the read face reports. {@code connectionId} is the discovered
 * connection's id (and the store key). {@code connectorId} is the connector it configures, carried for
 * display. {@code discoveredAt} is epoch milliseconds — the stored model's own freshness witness, since
 * a stale model stays visible until the next discovery replaces it. An immutable value carrying no
 * connector-framework types.
 */
public record DiscoveredSourceModel(String connectionId, String connectorId, long discoveredAt, SourceModel model) {

    public DiscoveredSourceModel {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(connectorId, "connectorId");
        Objects.requireNonNull(model, "model");
    }
}
