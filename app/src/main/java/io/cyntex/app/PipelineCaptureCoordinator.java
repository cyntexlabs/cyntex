package io.cyntex.app;

/**
 * Runs a pipeline's source-side capture alongside its Jet job. When a pipeline starts, its cdc capture must
 * run so the per-table change rings its topology reads are actually filled; when it stops, that capture must
 * be torn down so no capture daemon leaks. The actuator drives both this and the engine, composing the two
 * so a start fills the ring before the job reads it and a stop stops the job before the capture behind it.
 */
interface PipelineCaptureCoordinator {

    /** Starts the cdc capture for every source the pipeline reads, retaining the live handles for a later stop. */
    void startCapture(String pipelineId);

    /** Stops the cdc capture started for the pipeline, tearing down each source run and releasing its chain. */
    void stopCapture(String pipelineId);
}
