package io.cyntex.app;

import io.cyntex.runtime.engine.Engine;
import io.cyntex.runtime.scheduler.LifecycleActuator;

import java.util.Objects;

/**
 * Binds the converge loop's lifecycle actuator seam to the Jet execution engine: each verb drives the
 * matching engine operation, and a start submits the topology the {@link DagSource} supplies for the
 * pipeline. This is the assembly-layer wiring of the data plane, which lets the runtime ring's converge
 * loop stay engine- and framework-free.
 */
final class EngineLifecycleActuator implements LifecycleActuator {

    private final Engine engine;
    private final DagSource dagSource;

    EngineLifecycleActuator(Engine engine, DagSource dagSource) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.dagSource = Objects.requireNonNull(dagSource, "dagSource");
    }

    @Override
    public void start(String pipelineId) {
        engine.submit(pipelineId, dagSource.dagFor(pipelineId));
    }

    @Override
    public void pause(String pipelineId) {
        engine.suspend(pipelineId);
    }

    @Override
    public void resume(String pipelineId) {
        engine.resume(pipelineId);
    }

    @Override
    public void stop(String pipelineId) {
        engine.cancel(pipelineId);
    }
}
