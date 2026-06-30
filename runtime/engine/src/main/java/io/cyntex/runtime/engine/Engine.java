package io.cyntex.runtime.engine;

/**
 * Data-plane execution engine entry point.
 *
 * <p>Placeholder reserving the module; not instantiable and carries no behavior yet. Pipeline
 * job execution is implemented here later. Rule R4: depends on core + spi (and the Hazelcast
 * fork) only — never on an adapter.
 */
public final class Engine {

    private Engine() {
    }
}
