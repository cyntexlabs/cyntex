package io.cyntex.runtime.scheduler;

import io.cyntex.core.lifecycle.Observation;
import io.cyntex.core.lifecycle.PipelineState;
import io.cyntex.core.lifecycle.StateJson;
import io.cyntex.spi.store.ObservationStore;
import io.cyntex.spi.store.StateStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * Publishes a pipeline's observation from its converged actual state: it reads the fenced checkpoint,
 * maps the wire-form state back to the lifecycle state, and writes the latest observation projection to
 * the observation store. This is the runtime side of the store-backed observation contract — the runtime
 * writes the observation, control reads it, and the two meet only at the store, never calling each other.
 * A pipeline with no checkpoint yet has nothing to observe and is left untouched (no empty doc is written).
 *
 * <p>The errorCount metric has two sources. On a converged pass it is derived from that same actual state —
 * 1 while the pipeline is FAILED, 0 otherwise — so a dead data-plane job is an observable statistic, not just
 * a log line. When a reconcile pass instead keeps throwing (its store is unreachable, so it never converges),
 * {@link #publishReconcileFailure} carries the consecutive-failure count as errorCount so the pipeline is
 * observable as broken rather than silently absent from the read face. Either way errorCount &gt; 0 means the
 * pipeline is unhealthy. recordCount and the per-table sink-acked positions come from injected sources: the
 * record count rides the numeric metrics map when a live job reports one and is absent otherwise, and the
 * positions ride their own String-valued map. A missing metric or position means its source is not wired,
 * expressed by absence rather than a sentinel; the snapshot dataset is likewise unavailable and published
 * empty (never faked). Republishing overwrites the latest projection in place — the observation is
 * current-state, not a time series, so the derived errorCount tracks the state and does not accumulate across
 * ticks (a recovered pipeline drops back to 0).
 */
public final class ObservationPublisher {

    private final StateStore state;
    private final ObservationStore observations;
    private final Function<String, OptionalLong> recordCounts;
    private final Function<String, Map<String, String>> positions;

    /**
     * A publisher with no metric or position source: recordCount stays absent and positions stay empty,
     * so it carries the state-derived errorCount alone. This is the shape callers used before those
     * sources were wired; the assembly point injects the real sources through the full constructor.
     */
    public ObservationPublisher(StateStore state, ObservationStore observations) {
        this(state, observations, id -> OptionalLong.empty(), id -> Map.of());
    }

    /**
     * A publisher wired to its live run-statistic sources: {@code recordCounts} yields the records a
     * pipeline's live job has driven to its sinks (empty when it has no live job), and {@code positions}
     * yields the durable per-table sink-acked source positions (empty when none). Both are ports so the
     * scheduler stays clear of the engine and the store that back them.
     */
    public ObservationPublisher(StateStore state, ObservationStore observations,
            Function<String, OptionalLong> recordCounts, Function<String, Map<String, String>> positions) {
        this.state = Objects.requireNonNull(state, "state");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.recordCounts = Objects.requireNonNull(recordCounts, "recordCounts");
        this.positions = Objects.requireNonNull(positions, "positions");
    }

    /** Publishes the pipeline's latest observation from its actual state; a no-op if it has no checkpoint. */
    public void publish(String pipelineId) {
        Objects.requireNonNull(pipelineId, "pipelineId");
        state.read(pipelineId).ifPresent(checkpoint -> {
            PipelineState actual = StateJson.parse(checkpoint.stateJson());
            observations.save(new Observation(
                    pipelineId, actual, metrics(pipelineId, actual), Map.of(), positions.apply(pipelineId)));
        });
    }

    /**
     * Publishes a reconcile-failure observation for a pipeline whose converge pass keeps throwing and so
     * never reaches {@link #publish}. Without it the read face stays empty and "permanently broken" cannot be
     * told apart from "still converging". The consecutive-failure count rides out as errorCount; the last
     * observed lifecycle state is preserved (or NEW when the pipeline has never been observed), since a
     * reconcile that could not run witnessed no state transition. The snapshot dataset is left unavailable and
     * the failure cause stays in the driver's log — neither fits the numeric metric map.
     */
    public void publishReconcileFailure(String pipelineId, long consecutiveFailures) {
        Objects.requireNonNull(pipelineId, "pipelineId");
        PipelineState lastState = observations.read(pipelineId).map(Observation::state).orElse(PipelineState.NEW);
        observations.save(new Observation(pipelineId, lastState, Map.of("errorCount", consecutiveFailures), null));
    }

    /**
     * The numeric run statistics for the pipeline: errorCount is derived from the actual state (a FAILED job
     * is one observable error, else zero) and is always present; recordCount is added from its source only
     * when a live job reports one, so its absence reads as "not wired" rather than a zero count.
     */
    private Map<String, Long> metrics(String pipelineId, PipelineState actual) {
        Map<String, Long> metrics = new HashMap<>();
        metrics.put("errorCount", actual == PipelineState.FAILED ? 1L : 0L);
        recordCounts.apply(pipelineId).ifPresent(count -> metrics.put("recordCount", count));
        return metrics;
    }
}
