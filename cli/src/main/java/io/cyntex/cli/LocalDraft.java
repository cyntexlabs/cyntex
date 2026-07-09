package io.cyntex.cli;

import java.util.Objects;

/**
 * One authored resource document to submit for a remote apply: its raw YAML text plus an origin label (a
 * filename) the server uses only to attribute a parse error back to where it came from. The CLI sends raw
 * text, never a parsed model — the server re-parses and re-validates — so this is the request-side value
 * the {@code apply} verb marshals into the apply body's {@code drafts} array.
 */
record LocalDraft(String source, String content) {

    LocalDraft {
        Objects.requireNonNull(content, "draft content");
    }
}
