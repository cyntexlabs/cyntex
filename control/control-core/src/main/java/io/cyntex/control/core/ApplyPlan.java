package io.cyntex.control.core;

import java.util.List;

/**
 * The result of validating and canonicalizing a batch of drafts: the artifacts an apply would
 * upsert, in submission order. Producing a plan performs no writes — comparing each hash against the
 * store and upserting the changed artifacts is the caller's next step.
 */
public record ApplyPlan(List<PreparedArtifact> artifacts) {

    public ApplyPlan {
        artifacts = List.copyOf(artifacts);
    }
}
