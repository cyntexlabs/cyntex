package io.cyntex.cli;

import java.util.Objects;

/**
 * The truth-layer view of one stored artifact a read returns: its id, kind, and canonical form as held by
 * the server (server-as-truth). The response-side value the {@code get} and {@code ls} verbs decode from
 * the server's JSON. The CLI carries no shared control type (rule R6: it reaches the server over HTTP
 * only), so this mirrors the server's stored-artifact shape independently.
 */
record RemoteArtifact(String id, String kind, String canonicalForm) {

    RemoteArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(canonicalForm, "canonicalForm");
    }
}
