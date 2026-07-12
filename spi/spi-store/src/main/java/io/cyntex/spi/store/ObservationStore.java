package io.cyntex.spi.store;

import io.cyntex.core.lifecycle.Observation;
import java.util.Optional;

/**
 * The per-pipeline observation store: persists one observation doc per pipeline — the latest read-only
 * projection of its state, metrics and snapshot progress. A pure interface over the observation model
 * in the core ring (rule R2); it exposes the persistence surface only.
 *
 * <p>An observation is a plain latest-state projection, not a fenced transition and not a time series,
 * so it is a plain upsert by pipeline id (last write wins) — the same shape as the desired-intent
 * store. The runtime publishes it after converging; the control read faces read it. {@link #save}
 * upserts the observation for its pipeline; {@link #read} returns the current observation for a
 * pipeline, or empty when none has been published.
 */
public interface ObservationStore {

    /** Upserts the observation for its pipeline id (last write wins). */
    void save(Observation observation);

    /** Returns the current observation for a pipeline, or empty if none has been published. */
    Optional<Observation> read(String pipelineId);
}
