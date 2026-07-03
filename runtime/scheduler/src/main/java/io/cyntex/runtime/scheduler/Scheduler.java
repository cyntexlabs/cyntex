package io.cyntex.runtime.scheduler;

/**
 * Scheduler entry point: the first convergence-side controller host.
 *
 * <p>Placeholder reserving the module; not instantiable and carries no behavior yet. The
 * watch-desired / converge-actual control loop is implemented here later. Rule R4: depends on
 * core + spi (and the Hazelcast fork) only — never on an adapter.
 */
public final class Scheduler {

    private Scheduler() {
    }
}
