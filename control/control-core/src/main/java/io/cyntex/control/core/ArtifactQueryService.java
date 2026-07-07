package io.cyntex.control.core;

import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.spi.store.ArtifactStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The resource-type-agnostic read side of the double-layer model: the store is the truth layer, and a
 * read returns an artifact as its canonical form straight from that layer — never from a local draft
 * (server-as-truth). {@link ApplyService} is the write side; this is its read peer, backing the artifact
 * read verbs (get / list).
 *
 * <p>The canonical form a read returns is produced by the same {@link CanonicalWriter} the offline
 * authoring path uses, so an applied artifact reads back byte-for-byte as its offline canonical form:
 * the online path reuses the one canonical contract rather than forking it. Reconstructing the stored
 * form is the store's concern; this layer only re-serializes the reconstructed resource to canonical.
 */
public final class ArtifactQueryService {

    private final ArtifactStore store;
    private final CanonicalWriter writer = new CanonicalWriter();

    public ArtifactQueryService(ArtifactStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Returns the stored artifact for the id as its canonical form, or empty when none is stored. */
    public Optional<StoredArtifact> get(String id) {
        Objects.requireNonNull(id, "id");
        return store.get(id).map(this::view);
    }

    /** Lists every stored artifact as its canonical form. */
    public List<StoredArtifact> list() {
        return store.list().stream().map(this::view).toList();
    }

    private StoredArtifact view(Resource resource) {
        return new StoredArtifact(resource.id(), resource.kind(), writer.write(resource));
    }
}
