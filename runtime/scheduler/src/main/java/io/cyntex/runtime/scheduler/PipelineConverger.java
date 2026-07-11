package io.cyntex.runtime.scheduler;

import io.cyntex.core.lifecycle.CasOutcome;
import io.cyntex.core.lifecycle.CheckpointDoc;
import io.cyntex.core.lifecycle.DesiredState;
import io.cyntex.core.lifecycle.PipelineState;
import io.cyntex.core.lifecycle.StateJson;
import io.cyntex.spi.store.DesiredStore;
import io.cyntex.spi.store.StateStore;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Converges a pipeline's actual state toward its desired intent. It reads the desired target, seeds
 * the actual checkpoint the first time a pipeline appears, and lands the target through the fencing
 * compare-and-swap — rebasing on the fresh epoch when a write is fenced, so a writer that lost its
 * place picks the current epoch back up before retrying. This is the desired/actual split at work:
 * the control side writes desired intent, this converge side writes actual state, and the two never
 * cross. A lost race is a fenced value the retry loop handles, not an error; the loop is bounded, so
 * a pipeline that is being written out from under it concedes the pass and lets the next one retry.
 */
public final class PipelineConverger {

    /** The retry ceiling for one pass: a fenced writer rebases up to this many times before conceding. */
    public static final int MAX_CAS_ATTEMPTS = 8;

    private final DesiredStore desired;
    private final StateStore state;
    private final Clock clock;

    public PipelineConverger(DesiredStore desired, StateStore state, Clock clock) {
        this.desired = Objects.requireNonNull(desired, "desired");
        this.state = Objects.requireNonNull(state, "state");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Drives the pipeline's actual state toward its current desired target, seeding it if new. */
    public ConvergeResult converge(String pipelineId) {
        Optional<DesiredState> intent = desired.read(pipelineId);
        if (intent.isEmpty()) {
            return ConvergeResult.nothingToDo();
        }
        return driveTo(pipelineId, intent.get().targetState(), true);
    }

    /**
     * Marks a running pipeline terminal once its bounded source is exhausted — a converge-side
     * transition, never a user verb. The exhaustion signal that calls this comes from the execution
     * engine; a pipeline that has never run has no checkpoint and is left untouched.
     */
    public ConvergeResult markCompleted(String pipelineId) {
        return driveTo(pipelineId, PipelineState.COMPLETED, false);
    }

    private ConvergeResult driveTo(String pipelineId, PipelineState target, boolean seedIfAbsent) {
        String targetJson = StateJson.of(target);
        CheckpointDoc current = state.read(pipelineId).orElse(null);
        if (current == null) {
            if (!seedIfAbsent) {
                return ConvergeResult.nothingToDo();
            }
            state.create(pipelineId, StateJson.of(PipelineState.NEW), clock.instant());
            current = requireCheckpoint(pipelineId);
        }
        if (current.stateJson().equals(targetJson)) {
            return ConvergeResult.converged(current);
        }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            CasOutcome outcome = state.compareAndSwap(pipelineId, current.epoch(), targetJson, clock.instant());
            if (outcome instanceof CasOutcome.Applied applied) {
                return ConvergeResult.converged(applied.next());
            }
            // Fenced: another writer moved the epoch on. Re-read it and rebase before retrying.
            current = requireCheckpoint(pipelineId);
            if (current.stateJson().equals(targetJson)) {
                return ConvergeResult.converged(current);
            }
        }
        return ConvergeResult.superseded();
    }

    private CheckpointDoc requireCheckpoint(String pipelineId) {
        return state.read(pipelineId)
                .orElseThrow(() -> new IllegalStateException("checkpoint vanished for pipeline " + pipelineId));
    }
}
