package io.cyntex.spi.store;

import io.cyntex.core.model.Resource;
import java.util.List;
import java.util.Optional;

/**
 * The artifact truth layer: the canonical, authoritative store of the resources a workspace applies.
 * A pure interface over the resource model; it depends on the core ring only (rule R2).
 *
 * <p>The identity throughout is the resource's top-level id. {@link #save} upserts a resource by
 * that id, and the stored form is canonical. {@link #get} returns the stored resource for an id, or
 * empty when none is stored. {@link #list} returns every stored resource.
 */
public interface ArtifactStore {

    /** Upserts the resource by its top-level id; the stored form is canonical. */
    void save(Resource artifact);

    /** Returns the stored resource for the id, or empty if none is stored. */
    Optional<Resource> get(String id);

    /** Lists every stored resource. */
    List<Resource> list();
}
