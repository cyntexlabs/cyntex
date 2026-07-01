package io.cyntex.core.lifecycle;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A test double for the checkpoint store: stands in for the future Mongo adapter by holding
 * checkpoints in memory and applying the pure {@link EpochCas}, so the fencing contract can be
 * witnessed without a database. Single-threaded, so the read-swap-store step is trivially atomic —
 * exactly the atomicity the real adapter gets from a conditional {@code findOneAndUpdate}.
 */
final class InMemoryCheckpointStore {

    private final Map<String, CheckpointDoc> docs = new HashMap<>();

    /** Seeds the store with a pipeline's initial checkpoint. */
    void insert(CheckpointDoc doc) {
        docs.put(doc.pipelineId(), doc);
    }

    /** The current checkpoint for a pipeline, or {@code null} if none was inserted. */
    CheckpointDoc read(String pipelineId) {
        return docs.get(pipelineId);
    }

    /**
     * Applies the fencing CAS: on {@link CasOutcome.Applied} the new doc replaces the stored one; on
     * {@link CasOutcome.Fenced} the store is left untouched. Returns the outcome either way.
     */
    CasOutcome compareAndSwap(String pipelineId, long expectedEpoch, String nextStateJson, Instant touchTime) {
        CasOutcome outcome = EpochCas.swap(docs.get(pipelineId), expectedEpoch, nextStateJson, touchTime);
        if (outcome instanceof CasOutcome.Applied applied) {
            docs.put(pipelineId, applied.next());
        }
        return outcome;
    }
}
