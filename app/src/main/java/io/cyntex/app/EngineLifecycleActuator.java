package io.cyntex.app;

import io.cyntex.runtime.engine.Engine;
import io.cyntex.runtime.scheduler.LifecycleActuator;

import java.util.Objects;
import java.util.Optional;

/**
 * Binds the converge loop's lifecycle actuator seam to the Jet execution engine and the source-side capture
 * coordinator, composing the two so the data plane runs end to end. This is the assembly-layer wiring of the
 * data plane, which lets the runtime ring's converge loop stay engine- and framework-free.
 *
 * <p>Each verb composes the two sides in the order the data flow requires:
 * <ul>
 *   <li>{@code start} fills the change ring first (capture) then submits the job that reads it (engine), so
 *       the topology never starts against an unfilled ring.</li>
 *   <li>{@code stop} cancels the job first (engine) then stops the capture behind it (coordinator), so the
 *       capture daemon is torn down only once nothing reads its ring.</li>
 *   <li>{@code pause} / {@code resume} are engine-only: the capture keeps running while a pipeline is paused,
 *       held back by the ring's headroom backpressure, and a resume replays the buffered ring.</li>
 * </ul>
 */
final class EngineLifecycleActuator implements LifecycleActuator {

    private final Engine engine;
    private final DagSource dagSource;
    private final PipelineCaptureCoordinator captureCoordinator;

    EngineLifecycleActuator(Engine engine, DagSource dagSource, PipelineCaptureCoordinator captureCoordinator) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.dagSource = Objects.requireNonNull(dagSource, "dagSource");
        this.captureCoordinator = Objects.requireNonNull(captureCoordinator, "captureCoordinator");
    }

    @Override
    public void start(String pipelineId) {
        captureCoordinator.startCapture(pipelineId);
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
        captureCoordinator.stopCapture(pipelineId);
    }

    @Override
    public Optional<Throwable> failure(String pipelineId) {
        return engine.failureOf(pipelineId);
    }
}
