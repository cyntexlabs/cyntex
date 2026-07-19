package io.cyntex.control.restapi;

import io.cyntex.control.core.PipelineMetrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The metrics read face on the wire: the pipeline id plus one open stat map. The control read model keeps the
 * numeric run statistics and the per-table source positions as two typed maps; the wire presents them as the
 * single open map the metrics contract describes, carrying the per-table positions under the
 * {@code perTableOffset} key (its value a {@code table -> opaque srcpos} map). That key is present only when a
 * position has been acked, mirroring the never-faked, empty-is-unavailable rule the numeric metrics follow.
 */
record PipelineMetricsResponse(String pipelineId, Map<String, Object> metrics) {

    static PipelineMetricsResponse of(PipelineMetrics metrics) {
        Map<String, Object> open = new LinkedHashMap<>(metrics.metrics());
        if (!metrics.positions().isEmpty()) {
            open.put("perTableOffset", metrics.positions());
        }
        return new PipelineMetricsResponse(metrics.pipelineId(), open);
    }
}
