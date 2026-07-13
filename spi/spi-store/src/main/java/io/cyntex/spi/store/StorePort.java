package io.cyntex.spi.store;

/**
 * The persistence port: one store surface with six concerns — the artifact truth layer, the pipeline
 * state store (whose transitions land only through the epoch-fencing compare-and-swap), the pipeline
 * desired-state store (plain upsert intent, the split counterpart to the state store), the connection
 * catalog, the per-pipeline observation store (plain upsert latest projection, read by the monitor read
 * faces), and the SRS meta store (one durable coordination record per mining chain). A pure interface
 * over the core ring only (rule R2); a store backend (a database adapter) implements the six sub-stores
 * behind it.
 */
public interface StorePort {

    /** The canonical, authoritative store of applied resources. */
    ArtifactStore artifacts();

    /** The pipeline state store; transitions land only through its fencing compare-and-swap. */
    StateStore state();

    /** The pipeline desired-state store; plain upsert intent, the split counterpart to {@link #state()}. */
    DesiredStore desired();

    /** The store of registered connection / connector-instance configurations. */
    CatalogStore catalog();

    /** The per-pipeline observation store; plain upsert latest projection, read by the monitor read faces. */
    ObservationStore observations();

    /** The SRS meta store: one durable offset / consumer-cursor / schema record per mining chain. */
    SrsMetaStore meta();
}
