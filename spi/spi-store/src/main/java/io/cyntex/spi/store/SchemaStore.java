package io.cyntex.spi.store;

import java.util.Optional;

/**
 * Persists the source model discovery normalizes off a connection, keyed by that connection's id: the
 * truth-layer store counterpart of {@link SchemaDiscoverer}. A pure interface (rule R2); it carries no
 * connector-framework or store-driver types.
 *
 * <p>The identity is the connection id. {@link #save} upserts the model for a connection latest-only,
 * so a re-discovery overwrites the previous model in place rather than accumulating versions;
 * {@link #get} returns the stored model for a connection, or empty when none has been discovered.
 */
public interface SchemaStore {

    /** Upserts the discovered source model for the connection id; latest-only, a re-discovery overwrites. */
    void save(String connectionId, SourceModel model);

    /** Returns the stored source model for the connection id, or empty if none has been discovered. */
    Optional<SourceModel> get(String connectionId);
}
