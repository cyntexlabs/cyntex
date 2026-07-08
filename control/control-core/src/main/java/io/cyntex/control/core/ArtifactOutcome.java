package io.cyntex.control.core;

import java.util.Objects;

/**
 * The outcome of upserting one artifact through apply: its id and kind, whether the apply created a
 * new artifact, updated an existing one, or was a no-op because the stored artifact's content hash was
 * unchanged, and the content hash of the applied canonical form. A face returns these so an author
 * sees which resources actually changed.
 */
public record ArtifactOutcome(String id, String kind, Change change, String contentHash) {

    /** Whether an apply created a new artifact, overwrote a changed one, or left an identical one untouched. */
    public enum Change {
        /** No artifact was stored under this id; the resource was written. */
        CREATED,
        /** A different artifact was stored under this id; it was overwritten. */
        UPDATED,
        /** The stored artifact's content hash equalled this one; no write was performed. */
        UNCHANGED
    }

    public ArtifactOutcome {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(contentHash, "contentHash");
    }
}
