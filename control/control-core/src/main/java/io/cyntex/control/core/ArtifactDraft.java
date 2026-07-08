package io.cyntex.control.core;

import java.util.Objects;

/**
 * One authored resource document submitted for apply: its raw YAML text plus an origin label used
 * only to attribute a parse error back to where it came from (e.g. a filename). The server re-parses
 * and re-validates the raw text — it never trusts a client-parsed model — which is why a draft
 * carries text, not a resource. A {@code null} source means an unnamed origin (the diagnostic then
 * carries no source label).
 */
public record ArtifactDraft(String source, String content) {

    public ArtifactDraft {
        Objects.requireNonNull(content, "draft content");
    }
}
