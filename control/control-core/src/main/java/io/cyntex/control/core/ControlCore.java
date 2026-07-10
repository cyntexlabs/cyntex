package io.cyntex.control.core;

/**
 * Control core entry point: the verb layer and single write entry.
 *
 * <p>Placeholder reserving the module; not instantiable and carries no behavior yet. The
 * apply / export / run / delete verbs are implemented here later. Rule R5: depends on core +
 * the storage port, stays framework-free (no Spring — Spring lives in rest-api), and reaches the
 * runtime only through the store — the one exception being the synchronous connection-probe
 * whitelist (a closed set of one), the sole compile reference it holds into the runtime ring.
 */
public final class ControlCore {

    private ControlCore() {
    }
}
