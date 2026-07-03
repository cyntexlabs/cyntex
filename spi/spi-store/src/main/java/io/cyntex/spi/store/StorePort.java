package io.cyntex.spi.store;

/**
 * Persistence port (artifact / state storage).
 *
 * <p>Placeholder marker reserving the port name; no methods are defined yet. The real
 * compare-and-swap write path is implemented behind this port by a store backend. Rule R2:
 * ports depend on the core ring only.
 */
public interface StorePort {
}
