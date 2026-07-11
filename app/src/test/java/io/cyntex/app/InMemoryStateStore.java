package io.cyntex.app;

import io.cyntex.core.lifecycle.CasOutcome;
import io.cyntex.core.lifecycle.CheckpointDoc;
import io.cyntex.core.lifecycle.EpochCas;
import io.cyntex.spi.store.StateStore;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory {@link StateStore} double for the convergence wiring tests, applying the real
 * {@link EpochCas} fence. A pipeline can be marked failing so a convergence pass over it throws,
 * to witness the driver isolating a per-pipeline failure.
 */
final class InMemoryStateStore implements StateStore {

    private final Map<String, CheckpointDoc> docs = new HashMap<>();
    private final Set<String> failing = new HashSet<>();

    void failFor(String pipelineId) {
        failing.add(pipelineId);
    }

    @Override
    public Optional<CheckpointDoc> read(String pipelineId) {
        if (failing.contains(pipelineId)) {
            throw new IllegalStateException("state read failed for " + pipelineId);
        }
        return Optional.ofNullable(docs.get(pipelineId));
    }

    @Override
    public void create(String pipelineId, String stateJson, Instant touchTime) {
        docs.put(pipelineId, CheckpointDoc.initial(pipelineId, stateJson, touchTime));
    }

    @Override
    public CasOutcome compareAndSwap(String pipelineId, long expectedEpoch, String nextStateJson, Instant touchTime) {
        CasOutcome outcome = EpochCas.swap(docs.get(pipelineId), expectedEpoch, nextStateJson, touchTime);
        if (outcome instanceof CasOutcome.Applied applied) {
            docs.put(pipelineId, applied.next());
        }
        return outcome;
    }
}
