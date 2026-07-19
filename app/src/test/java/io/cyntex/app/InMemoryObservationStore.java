package io.cyntex.app;

import io.cyntex.core.lifecycle.Observation;
import io.cyntex.spi.store.ObservationStore;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** In-memory {@link ObservationStore} double for the convergence wiring tests; insertion-ordered. */
final class InMemoryObservationStore implements ObservationStore {

    private final Map<String, Observation> docs = new LinkedHashMap<>();
    private final Set<String> failingSaves = new HashSet<>();

    void failSaveFor(String pipelineId) {
        failingSaves.add(pipelineId);
    }

    @Override
    public void save(Observation observation) {
        if (failingSaves.contains(observation.pipelineId())) {
            throw new IllegalStateException("observation save failed for " + observation.pipelineId());
        }
        docs.put(observation.pipelineId(), observation);
    }

    @Override
    public Optional<Observation> read(String pipelineId) {
        return Optional.ofNullable(docs.get(pipelineId));
    }
}
