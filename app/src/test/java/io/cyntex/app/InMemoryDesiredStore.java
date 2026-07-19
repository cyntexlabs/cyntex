package io.cyntex.app;

import io.cyntex.core.lifecycle.DesiredState;
import io.cyntex.spi.store.DesiredStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link DesiredStore} double for the convergence wiring tests; insertion-ordered. */
final class InMemoryDesiredStore implements DesiredStore {

    private final Map<String, DesiredState> docs = new LinkedHashMap<>();

    @Override
    public void save(DesiredState desired) {
        docs.put(desired.pipelineId(), desired);
    }

    void remove(String pipelineId) {
        docs.remove(pipelineId);
    }

    @Override
    public Optional<DesiredState> read(String pipelineId) {
        return Optional.ofNullable(docs.get(pipelineId));
    }

    @Override
    public List<String> pipelineIds() {
        return new ArrayList<>(docs.keySet());
    }
}
