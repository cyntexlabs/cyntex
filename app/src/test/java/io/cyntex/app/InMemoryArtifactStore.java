package io.cyntex.app;

import io.cyntex.core.model.Resource;
import io.cyntex.spi.store.ArtifactStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link ArtifactStore} for the assembly-layer tests: a batch upsert keyed by top-level id,
 * with the single-artifact {@code save} inherited from the port. Enough for the data-plane tests to seed a
 * pipeline and its referenced sources without a store backend.
 */
final class InMemoryArtifactStore implements ArtifactStore {

    private final Map<String, Resource> byId = new LinkedHashMap<>();

    @Override
    public void saveAll(List<Resource> artifacts) {
        for (Resource artifact : artifacts) {
            byId.put(artifact.id(), artifact);
        }
    }

    @Override
    public Optional<Resource> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Resource> list() {
        return List.copyOf(byId.values());
    }
}
