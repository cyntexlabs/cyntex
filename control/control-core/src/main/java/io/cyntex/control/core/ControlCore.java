package io.cyntex.control.core;

/**
 * Control core entry point: the verb layer and single write entry.
 *
 * <p>Placeholder reserving the module; not instantiable and carries no behavior yet. The
 * apply / export / run / delete verbs are implemented here later. Rule R5: depends on core +
 * the storage port; never holds a compile dependency on the runtime ring.
 */
public final class ControlCore {

    private ControlCore() {
    }
}
