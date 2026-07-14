package io.cyntex.control.core;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.lifecycle.Observation;
import io.cyntex.spi.store.ObservationStore;

import java.util.Map;
import java.util.Objects;

/**
 * The pipeline observation read side: the three store-backed read faces — status / metrics / snapshot —
 * each a projection of the one per-pipeline observation the runtime publishes. The read peer of the
 * lifecycle write side; it reads the observation store only and never calls the runtime (control and
 * runtime meet only through the store). A read of a pipeline that has published no observation is a
 * coded {@code monitor.no-observation} diagnostic, so the same read serves a frontend with no
 * stderr/exit channel rather than a bare usage error.
 */
public final class PipelineObservationQueryService {

    private final ObservationStore observations;

    public PipelineObservationQueryService(ObservationStore observations) {
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    /** The pipeline's lifecycle state. */
    public PipelineStatus status(String pipelineId) {
        Observation observation = require(pipelineId);
        return new PipelineStatus(observation.pipelineId(), observation.state());
    }

    /** The pipeline's open map of run statistics. */
    public PipelineMetrics metrics(String pipelineId) {
        Observation observation = require(pipelineId);
        return new PipelineMetrics(observation.pipelineId(), observation.metrics());
    }

    /** The pipeline's per-table initial-load progress. */
    public PipelineSnapshot snapshot(String pipelineId) {
        Observation observation = require(pipelineId);
        return new PipelineSnapshot(observation.pipelineId(), observation.snapshot());
    }

    private Observation require(String pipelineId) {
        Objects.requireNonNull(pipelineId, "pipelineId");
        return observations.read(pipelineId).orElseThrow(() -> new CyntexException(
                MonitorError.NO_OBSERVATION, Map.of("pipeline", pipelineId), null));
    }
}
