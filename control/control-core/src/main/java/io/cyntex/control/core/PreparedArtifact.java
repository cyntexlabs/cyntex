package io.cyntex.control.core;

import io.cyntex.core.model.Resource;

import java.util.Objects;

/**
 * A validated resource together with its canonical form and content hash — the unit an upsert
 * consumes. The canonical form is what gets stored; the content hash gates no-op vs a new revision
 * (equal hash to the stored artifact = no write). Identity stays the resource's top-level id.
 */
public record PreparedArtifact(Resource resource, String canonicalForm, String contentHash) {

    public PreparedArtifact {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(canonicalForm, "canonicalForm");
        Objects.requireNonNull(contentHash, "contentHash");
    }

    /** The resource's top-level id (the upsert key). */
    public String id() {
        return resource.id();
    }

    /** The resource kind discriminator (source / pipeline / transform / view / serve). */
    public String kind() {
        return resource.kind();
    }
}
