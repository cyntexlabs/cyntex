package io.cyntex.core.lifecycle;

import java.util.Objects;

/**
 * The per-pipeline desired intent: one doc per pipeline stating the target state a user asked the
 * pipeline to reach, at the artifact revision that intent was expressed against. This is the desired
 * half of the split; the actual half is the epoch-fenced {@link CheckpointDoc}. Desired is plain
 * intent, written by the control side and never epoch-fenced; the converge side reads it and drives
 * the actual state toward it. The shape is an external contract — adding a field is backward
 * compatible, changing or removing one is a breaking change. The real Mongo serialization lives in an
 * adapter; this record is the shape.
 *
 * <ul>
 *   <li>{@code pipelineId} — the primary key, one desired doc per pipeline.</li>
 *   <li>{@code targetState} — the state the user wants the pipeline to reach.</li>
 *   <li>{@code revision} — the artifact revision the intent was expressed against.</li>
 * </ul>
 */
public record DesiredState(String pipelineId, PipelineState targetState, String revision) {

    public DesiredState {
        Objects.requireNonNull(pipelineId, "pipelineId");
        Objects.requireNonNull(targetState, "targetState");
        Objects.requireNonNull(revision, "revision");
    }
}
