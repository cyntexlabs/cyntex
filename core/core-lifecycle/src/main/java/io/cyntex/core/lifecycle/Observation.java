package io.cyntex.core.lifecycle;

import java.util.Map;
import java.util.Objects;

/**
 * The per-pipeline observation: the latest read-only projection of a running pipeline's state,
 * metrics and snapshot progress, keyed by pipeline id. One doc per pipeline, overwritten in place
 * (latest wins, not a time series). The runtime publishes it; the control read faces read it. The
 * shape is an external contract — adding a metric is a map entry, not a shape change; adding a field
 * is backward compatible, changing or removing one is breaking. The real Mongo serialization lives in
 * an adapter; this record is the shape.
 *
 * <ul>
 *   <li>{@code pipelineId} — the primary key, one observation per pipeline.</li>
 *   <li>{@code state} — the lifecycle state, the small stable status dataset.</li>
 *   <li>{@code metrics} — an open map of run statistics ({@code name -> value}); empty when none are
 *       wired yet (unavailable), never faked.</li>
 *   <li>{@code snapshot} — per-table initial-load progress; empty outside a snapshot phase or when
 *       unavailable.</li>
 * </ul>
 */
public record Observation(
        String pipelineId, PipelineState state, Map<String, Long> metrics, Map<String, TableSnapshot> snapshot) {

    public Observation {
        Objects.requireNonNull(pipelineId, "pipelineId");
        Objects.requireNonNull(state, "state");
        // A state-only observation is normal before metric / snapshot sources are wired: null reads as an
        // empty (unavailable) map, and the copy makes the stored projection immutable.
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        snapshot = snapshot == null ? Map.of() : Map.copyOf(snapshot);
    }
}
