package io.cyntex.runtime.scheduler;

import io.cyntex.core.lifecycle.Observation;
import io.cyntex.core.lifecycle.StateJson;
import io.cyntex.spi.store.ObservationStore;
import io.cyntex.spi.store.StateStore;

import java.util.Map;
import java.util.Objects;

/**
 * Publishes a pipeline's observation from its converged actual state: it reads the fenced checkpoint,
 * maps the wire-form state back to the lifecycle state, and writes the latest observation projection to
 * the observation store. This is the runtime side of the store-backed observation contract — the runtime
 * writes the observation, control reads it, and the two meet only at the store, never calling each other.
 * A pipeline with no checkpoint yet has nothing to observe and is left untouched (no empty doc is written).
 *
 * <p>L1 publishes the state only: the metric and snapshot datasets are empty until their sources are
 * wired, reported as unavailable rather than faked. Republishing overwrites the latest projection in
 * place — the observation is current-state, not a time series.
 */
public final class ObservationPublisher {

    private final StateStore state;
    private final ObservationStore observations;

    public ObservationPublisher(StateStore state, ObservationStore observations) {
        this.state = Objects.requireNonNull(state, "state");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    /** Publishes the pipeline's latest observation from its actual state; a no-op if it has no checkpoint. */
    public void publish(String pipelineId) {
        Objects.requireNonNull(pipelineId, "pipelineId");
        state.read(pipelineId).ifPresent(checkpoint -> observations.save(
                new Observation(pipelineId, StateJson.parse(checkpoint.stateJson()), Map.of(), Map.of())));
    }
}
