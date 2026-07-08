package io.cyntex.control.core;

import java.util.List;

/**
 * The result of applying a batch of drafts: one outcome per artifact, in submission order — whether
 * each was created, updated, or an unchanged no-op. Producing this has already performed the upserts;
 * the outcomes report what the store did.
 */
public record ApplyResult(List<ArtifactOutcome> outcomes) {

    public ApplyResult {
        outcomes = List.copyOf(outcomes);
    }
}
