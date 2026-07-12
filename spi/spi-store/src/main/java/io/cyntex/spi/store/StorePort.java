package io.cyntex.spi.store;

/**
 * The persistence port: one store surface with seven concerns — the artifact truth layer, the
 * pipeline state store (whose transitions land only through the epoch-fencing compare-and-swap),
 * the connection catalog, the discovered source-schema store, the connector distribution registry,
 * the latest connection-test result per connection, and the SRS meta store (one durable coordination
 * record per mining chain). A pure interface over the core ring only (rule R2); a store backend
 * (a database adapter) implements the sub-stores behind it.
 */
public interface StorePort {

    /** The canonical, authoritative store of applied resources. */
    ArtifactStore artifacts();

    /** The pipeline state store; transitions land only through its fencing compare-and-swap. */
    StateStore state();

    /** The store of registered connection / connector-instance configurations. */
    CatalogStore catalog();

    /** The store of source models discovered off registered connections. */
    SchemaStore schemas();

    /** The connector distribution registry: registered connector artifacts and their bytes. */
    ConnectorRegistry connectors();

    /** The store of the latest connection-test result per connection. */
    ConnectionTestResultStore connectionTestResults();

    /** The SRS meta store: one durable offset / consumer-cursor / schema record per mining chain. */
    SrsMetaStore meta();
}
