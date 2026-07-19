package io.cyntex.control.core;

import java.util.Map;
import java.util.Objects;

/**
 * The metrics read face: a pipeline's open map of numeric run statistics ({@code name -> value}) plus its
 * per-table source positions ({@code table -> opaque srcpos}). The field set is deliberately not fixed —
 * adding a metric is a map entry, not a contract-shape change. Positions ride in their own map rather than
 * the numeric one because a srcpos (binlog/GTID/LSN) is an opaque String, not a count; a read face presents
 * the two together. Either map is empty when its source is not wired yet (unavailable), never faked.
 */
public record PipelineMetrics(String pipelineId, Map<String, Long> metrics, Map<String, String> positions) {

    public PipelineMetrics {
        Objects.requireNonNull(pipelineId, "pipelineId");
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        positions = positions == null ? Map.of() : Map.copyOf(positions);
    }
}
