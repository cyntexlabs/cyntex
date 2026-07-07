package io.cyntex.spi.store;

import io.cyntex.core.model.Resource;
import java.util.List;
import java.util.Optional;

/**
 * The artifact truth layer: the canonical, authoritative store of the resources a workspace applies.
 * A pure interface over the resource model; it depends on the core ring only (rule R2).
 *
 * <p>The identity throughout is the resource's top-level id. {@link #saveAll} upserts a batch of
 * resources by that id as one atomic unit — either every one is stored or, on any failure, none is —
 * and the stored form is canonical; {@link #save} is the single-artifact case of it. {@link #get}
 * returns the stored resource for an id, or empty when none is stored. {@link #list} returns every
 * stored resource.
 */
public interface ArtifactStore {

    /**
     * Atomically upserts every resource in {@code artifacts} by its top-level id: either all are
     * stored or, on any failure, none is — there is no partial batch. The stored form is canonical, and
     * an empty batch writes nothing. Ordering follows the list, though the atomic outcome does not
     * depend on it.
     */
    void saveAll(List<Resource> artifacts);

    /** Upserts a single resource by its top-level id — the single-artifact case of {@link #saveAll}. */
    default void save(Resource artifact) {
        saveAll(List.of(artifact));
    }

    /** Returns the stored resource for the id, or empty if none is stored. */
    Optional<Resource> get(String id);

    /** Lists every stored resource. */
    List<Resource> list();
}
