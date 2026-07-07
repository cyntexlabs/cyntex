package io.cyntex.control.core;

import java.util.Objects;

/**
 * The truth-layer view of one stored artifact returned by a read: its id, kind, and canonical form as
 * held by the store. This is what a face shows for the artifact read verbs — the server, not a local
 * draft, is the source of what an artifact is (server-as-truth). The read peer of {@link PreparedArtifact}.
 */
public record StoredArtifact(String id, String kind, String canonicalForm) {

    public StoredArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(canonicalForm, "canonicalForm");
    }
}
