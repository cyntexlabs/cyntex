package io.cyntex.runtime.scheduler;

import io.cyntex.core.lifecycle.Observation;
import io.cyntex.core.lifecycle.PipelineState;
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
 * <p>The errorCount metric is derived from that same actual state — 1 while the pipeline is FAILED, 0
 * otherwise — so a dead data-plane job is an observable statistic, not just a log line. The remaining run
 * statistics have no source wired yet and are absent from the map; the snapshot dataset is likewise
 * unavailable and published empty (never faked). Republishing overwrites the latest projection in place —
 * the observation is current-state, not a time series, so the derived errorCount tracks the state and does
 * not accumulate across ticks (a recovered pipeline drops back to 0).
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
        state.read(pipelineId).ifPresent(checkpoint -> {
            PipelineState actual = StateJson.parse(checkpoint.stateJson());
            observations.save(new Observation(pipelineId, actual, metrics(actual), Map.of()));
        });
    }

    /** The run statistics derived from the actual state: a FAILED job is one observable error, else zero. */
    private static Map<String, Long> metrics(PipelineState actual) {
        return Map.of("errorCount", actual == PipelineState.FAILED ? 1L : 0L);
    }
}
