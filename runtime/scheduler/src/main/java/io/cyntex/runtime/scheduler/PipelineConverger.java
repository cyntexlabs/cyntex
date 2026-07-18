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
    private final LifecycleActuator actuator;
    private final Clock clock;

    public PipelineConverger(DesiredStore desired, StateStore state, LifecycleActuator actuator, Clock clock) {
        this.desired = Objects.requireNonNull(desired, "desired");
        this.state = Objects.requireNonNull(state, "state");
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Drives the pipeline's actual state toward its current desired target, seeding it if new. */
    public ConvergeResult converge(String pipelineId) {
        Optional<DesiredState> intent = desired.read(pipelineId);
        if (intent.isEmpty()) {
            return ConvergeResult.nothingToDo();
        }
        PipelineState target = intent.get().targetState();
        Optional<CheckpointDoc> actualDoc = state.read(pipelineId);
        PipelineState actual = actualDoc.map(doc -> StateJson.parse(doc.stateJson())).orElse(null);

        if (target == PipelineState.RUNNING && actual == PipelineState.RUNNING) {
            // A pipeline believed running whose job has died converges to the observable FAILED state,
            // rather than reporting RUNNING over a dead job. The failure cause rides out on the result so
            // the driver can surface it. A converge-side transition, never a user verb.
            Optional<Throwable> failure = actuator.failure(pipelineId);
            if (failure.isPresent()) {
                ConvergeResult driven = driveTo(pipelineId, PipelineState.FAILED, false, actualDoc.orElse(null));
                return driven.checkpoint()
                        .map(checkpoint -> ConvergeResult.failed(checkpoint, failure.get()))
                        .orElse(driven);
            }
        }

        if (target == PipelineState.RUNNING && actual == PipelineState.FAILED) {
            // A failed run stays failed: re-driving it toward RUNNING would restart the dead job on
            // every tick. The user recovers by stopping it (a STOPPED target, driven below) then
            // starting a fresh run.
            return ConvergeResult.converged(actualDoc.get());
        }

        return driveTo(pipelineId, target, true, actualDoc.orElse(null));
    }

    /**
     * Marks a running pipeline terminal once its bounded source is exhausted — a converge-side
     * transition, never a user verb. The exhaustion signal that calls this comes from the execution
     * engine; a pipeline that has never run has no checkpoint and is left untouched. A pipeline marked
     * completed must then be dropped from the reconcile set (or its desired intent advanced to match),
     * or a later convergence pass would drive its actual state back toward a non-terminal desired target.
     */
    public ConvergeResult markCompleted(String pipelineId) {
        return driveTo(pipelineId, PipelineState.COMPLETED, false, state.read(pipelineId).orElse(null));
    }

    private ConvergeResult driveTo(
            String pipelineId, PipelineState target, boolean seedIfAbsent, CheckpointDoc current) {
        String targetJson = StateJson.of(target);
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
            PipelineState from = StateJson.parse(current.stateJson());
            CasOutcome outcome = state.compareAndSwap(pipelineId, current.epoch(), targetJson, clock.instant());
            if (outcome instanceof CasOutcome.Applied applied) {
                // Record first, then actuate: the store is the source of truth and Jet is subordinate, so the
                // fenced write lands the intent durably before the job side is driven to match it.
                actuate(pipelineId, from, target);
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

    /**
     * Drives the job side to match a state transition the store just recorded. Start and resume both
     * land in RUNNING, so the origin state decides between them: RUNNING reached from PAUSED continues
     * the held job (resume), reached from anywhere else begins a fresh run (start). A pipeline seeded at
     * NEW is never a transition target here, so it drives nothing.
     */
    private void actuate(String pipelineId, PipelineState from, PipelineState target) {
        switch (target) {
            case RUNNING -> {
                if (from == PipelineState.PAUSED) {
                    actuator.resume(pipelineId);
                } else {
                    actuator.start(pipelineId);
                }
            }
            case PAUSED -> actuator.pause(pipelineId);
            case STOPPED, COMPLETED, FAILED -> actuator.stop(pipelineId);
            case NEW -> {
                // The seed state is written through create(), never a compare-and-swap target, so it
                // never reaches this actuation path.
            }
        }
    }

    private CheckpointDoc requireCheckpoint(String pipelineId) {
        return state.read(pipelineId)
                .orElseThrow(() -> new IllegalStateException("checkpoint vanished for pipeline " + pipelineId));
    }
}
