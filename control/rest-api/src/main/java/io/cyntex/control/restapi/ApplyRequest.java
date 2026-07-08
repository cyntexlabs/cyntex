package io.cyntex.control.restapi;

import io.cyntex.control.core.ArtifactDraft;

import java.util.List;

/**
 * The apply request body: the batch of authored drafts to apply as one unit. Each draft carries its raw
 * YAML text (and an optional source label), which the server re-parses and re-validates — it never
 * trusts a client-parsed model. A wrapper object rather than a bare array so the request can grow
 * fields (dry-run, options) without changing the wire shape.
 */
public record ApplyRequest(List<ArtifactDraft> drafts) {
}
