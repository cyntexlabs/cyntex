package io.cyntex.spi.store;

import io.cyntex.core.lifecycle.DesiredState;
import java.util.Optional;

/**
 * The pipeline desired-state store: persists one desired-intent doc per pipeline — the target state a
 * user asked the pipeline to reach, at the artifact revision that intent was expressed against. A pure
 * interface over the desired-intent model in the core ring (rule R2); it exposes the persistence
 * surface only and does not decide convergence.
 *
 * <p>Desired intent is the split-off counterpart to the epoch-fencing pipeline state store: it is
 * plain intent, not a fenced transition, so it is a plain upsert by pipeline id rather than a
 * compare-and-swap. {@link #save} upserts the desired doc for its pipeline (last write wins);
 * {@link #read} returns the current desired doc for a pipeline, or empty when none is set.
 */
public interface DesiredStore {

    /** Upserts the desired intent for its pipeline id (last write wins). */
    void save(DesiredState desired);

    /** Returns the current desired intent for a pipeline, or empty if none is set. */
    Optional<DesiredState> read(String pipelineId);
}
