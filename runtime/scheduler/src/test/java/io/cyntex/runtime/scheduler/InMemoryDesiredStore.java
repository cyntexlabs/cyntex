package io.cyntex.runtime.scheduler;

import io.cyntex.core.lifecycle.DesiredState;
import io.cyntex.spi.store.DesiredStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A trivial in-memory {@link DesiredStore} double: a last-write-wins map keyed by pipeline id. */
final class InMemoryDesiredStore implements DesiredStore {

    private final Map<String, DesiredState> docs = new HashMap<>();

    @Override
    public void save(DesiredState desired) {
        docs.put(desired.pipelineId(), desired);
    }

    @Override
    public Optional<DesiredState> read(String pipelineId) {
        return Optional.ofNullable(docs.get(pipelineId));
    }

    @Override
    public List<DesiredState> list() {
        return List.copyOf(docs.values());
    }
}
