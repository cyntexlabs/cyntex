package io.cyntex.control.core;

import io.cyntex.core.lifecycle.PipelineState;
import java.util.Objects;

/**
 * The status read face: a pipeline's lifecycle state, the small stable observation dataset. Carries
 * only the state (never counts / rates / snapshot progress), so the status contract evolves with the
 * state machine, not with the growing metric set.
 */
public record PipelineStatus(String pipelineId, PipelineState state) {

    public PipelineStatus {
        Objects.requireNonNull(pipelineId, "pipelineId");
        Objects.requireNonNull(state, "state");
    }
}
