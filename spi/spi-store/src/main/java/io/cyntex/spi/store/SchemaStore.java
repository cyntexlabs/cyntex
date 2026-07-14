package io.cyntex.spi.store;

import java.util.Optional;

/**
 * Persists the source model discovery normalizes off a connection, keyed by that connection's id: the
 * truth-layer store counterpart of {@link SchemaDiscoverer}. A pure interface (rule R2); it carries no
 * connector-framework or store-driver types.
 *
 * <p>The stored unit is the {@link DiscoveredSourceModel} envelope — the model plus the connector id
 * and discovery time the read face reports. The identity is the connection id. {@link #save} upserts
 * the envelope for its connection latest-only, so a re-discovery overwrites the previous one in place
 * rather than accumulating versions; {@link #get} returns the stored envelope for a connection, or
 * empty when none has been discovered.
 */
public interface SchemaStore {

    /** Upserts the discovery envelope for its connection id; latest-only, a re-discovery overwrites. */
    void save(DiscoveredSourceModel discovered);

    /** Returns the stored discovery envelope for the connection id, or empty if none has been discovered. */
    Optional<DiscoveredSourceModel> get(String connectionId);
}
