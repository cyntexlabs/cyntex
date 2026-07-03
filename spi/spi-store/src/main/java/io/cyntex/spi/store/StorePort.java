package io.cyntex.spi.store;

/**
 * The persistence port: one store surface with three concerns — the artifact truth layer, the
 * pipeline state store (whose transitions land only through the epoch-fencing compare-and-swap),
 * and the connection catalog. A pure interface over the core ring only (rule R2); a store backend (a
 * database adapter) implements the three sub-stores behind it.
 */
public interface StorePort {

    /** The canonical, authoritative store of applied resources. */
    ArtifactStore artifacts();

    /** The pipeline state store; transitions land only through its fencing compare-and-swap. */
    StateStore state();

    /** The store of registered connection / connector-instance configurations. */
    CatalogStore catalog();
}
