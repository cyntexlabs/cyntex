package io.cyntex.app;

import io.cyntex.core.lifecycle.Observation;
import io.cyntex.spi.store.ObservationStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link ObservationStore} double for the convergence wiring tests; insertion-ordered. */
final class InMemoryObservationStore implements ObservationStore {

    private final Map<String, Observation> docs = new LinkedHashMap<>();

    @Override
    public void save(Observation observation) {
        docs.put(observation.pipelineId(), observation);
    }

    @Override
    public Optional<Observation> read(String pipelineId) {
        return Optional.ofNullable(docs.get(pipelineId));
    }
}
