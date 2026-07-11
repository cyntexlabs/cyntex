package io.cyntex.spi.store;

/**
 * Discovers the metadata a stored connection's source exposes by driving the configured connector's
 * schema discovery and normalizing it into a {@link SourceModel}: the streams (tables) with their
 * fields, primary key and indexes.
 *
 * <p>Only a failure that prevents discovery from running at all (the connector cannot be loaded /
 * level-gated, or throws out of its own discovery) surfaces as a coded exception. The port carries no
 * connector-framework types.
 */
public interface SchemaDiscoverer {

    /** Discovers the source model {@code config}'s connector exposes. */
    SourceModel discover(ConnectionConfig config);
}
