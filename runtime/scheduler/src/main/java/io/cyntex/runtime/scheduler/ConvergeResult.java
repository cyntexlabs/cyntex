package io.cyntex.runtime.scheduler;

import io.cyntex.core.lifecycle.CheckpointDoc;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of one convergence pass: its {@link ConvergeStatus} and, when the pass ended at the
 * target, the resulting checkpoint. The checkpoint is present only for {@link ConvergeStatus#CONVERGED}.
 */
public record ConvergeResult(ConvergeStatus status, Optional<CheckpointDoc> checkpoint) {

    public ConvergeResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checkpoint, "checkpoint");
    }

    static ConvergeResult converged(CheckpointDoc checkpoint) {
        return new ConvergeResult(ConvergeStatus.CONVERGED, Optional.of(checkpoint));
    }

    static ConvergeResult nothingToDo() {
        return new ConvergeResult(ConvergeStatus.NOTHING_TO_DO, Optional.empty());
    }

    static ConvergeResult superseded() {
        return new ConvergeResult(ConvergeStatus.SUPERSEDED, Optional.empty());
    }
}
