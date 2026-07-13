package io.cyntex.control.core;

import java.util.Map;
import java.util.Objects;

/**
 * The metrics read face: a pipeline's open map of run statistics ({@code name -> value}). The field set
 * is deliberately not fixed — adding a metric is a map entry, not a contract-shape change. Empty when no
 * metric source is wired yet (unavailable), never faked.
 */
public record PipelineMetrics(String pipelineId, Map<String, Long> metrics) {

    public PipelineMetrics {
        Objects.requireNonNull(pipelineId, "pipelineId");
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
