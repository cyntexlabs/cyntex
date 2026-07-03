package io.cyntex.spi.store;

import io.cyntex.core.lifecycle.CasOutcome;
import io.cyntex.core.lifecycle.CheckpointDoc;
import java.time.Instant;
import java.util.Optional;

/**
 * The pipeline state store: persists one checkpoint per pipeline, whose transitions land only
 * through the epoch-fencing compare-and-swap. A pure interface over the checkpoint model in the
 * core ring (rule R2); it exposes the persistence surface only and does not define the state machine.
 *
 * <p>{@link #create} seeds a pipeline's first checkpoint at epoch 0 — the compare-and-swap cannot
 * bootstrap from an absent checkpoint. It is insert-only: it must not overwrite an existing
 * checkpoint, because doing so would reset the fencing epoch that {@link #compareAndSwap} maintains.
 * Seeding a pipeline that already has a checkpoint is a programmer / ordering error; a caller that
 * needs to know can {@link #read} first. It takes no epoch, so a seed is always epoch 0.
 *
 * <p>{@link #compareAndSwap} is the single legal transition write: it succeeds only when
 * {@code expectedEpoch} equals the stored epoch, incrementing the epoch on success and fencing any
 * stale writer otherwise, so two owners that both believe they hold the pipeline can never both
 * write. The state carrier is the opaque {@code stateJson}; the fencing decision is made on the
 * epoch alone, never on the state, so a lagging clock or a stale state view can never corrupt it.
 */
public interface StateStore {

    /** Returns the current checkpoint for a pipeline, or empty if none exists. */
    Optional<CheckpointDoc> read(String pipelineId);

    /**
     * Seeds a pipeline's first checkpoint, at epoch 0, before any compare-and-swap has run.
     * Insert-only: it must not overwrite an existing checkpoint (which would reset the fencing
     * epoch that {@link #compareAndSwap} maintains).
     */
    void create(String pipelineId, String stateJson, Instant touchTime);

    /**
     * Attempts the fencing swap. On a matching {@code expectedEpoch} the stored state becomes
     * {@code nextStateJson}, the epoch increments, and the touch time refreshes; otherwise the
     * store is left untouched. Returns {@link CasOutcome.Applied} with the new checkpoint, or
     * {@link CasOutcome.Fenced} carrying the stored epoch that superseded the writer.
     */
    CasOutcome compareAndSwap(String pipelineId, long expectedEpoch, String nextStateJson, Instant touchTime);
}
