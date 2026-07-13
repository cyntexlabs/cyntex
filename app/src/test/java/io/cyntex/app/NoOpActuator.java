package io.cyntex.app;

import io.cyntex.runtime.scheduler.LifecycleActuator;

/**
 * A {@link LifecycleActuator} that drives nothing — lets a wiring test exercise the convergence loop
 * without standing up a Jet member, since those tests assert bean wiring and state convergence, not the
 * data-plane job side.
 */
final class NoOpActuator implements LifecycleActuator {

    @Override
    public void start(String pipelineId) {
    }

    @Override
    public void pause(String pipelineId) {
    }

    @Override
    public void resume(String pipelineId) {
    }

    @Override
    public void stop(String pipelineId) {
    }
}
