package io.cyntex.spi.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One consumer pipeline's cursor into a mining chain's change stream. It carries two quantities of
 * different lifetime: {@code perTableSeq} — the run-local read cursor into each per-table ring (a
 * table-to-sequence map; not stable across a restart, because a re-mine allocates a fresh sequence
 * space) — and {@code sinkAckedSrcpos} — the opaque source position durably acked to the pipeline's
 * sink (stable across a restart; the quantity a source-read-offset advance is bounded by). The acked
 * position is absent until the pipeline's sink first acks a change.
 *
 * <p>A pure value over {@code java..} only (rule R2): source positions travel as opaque tokens, never
 * as a connector type.
 */
public record ConsumerOffset(String pipelineId, Map<String, Long> perTableSeq, String sinkAckedSrcpos) {

    public ConsumerOffset {
        if (pipelineId == null || pipelineId.isBlank()) {
            throw new IllegalArgumentException("consumer offset pipelineId must be non-blank");
        }
        if (perTableSeq == null) {
            throw new IllegalArgumentException("consumer offset perTableSeq must be set");
        }
        perTableSeq = Collections.unmodifiableMap(new LinkedHashMap<>(perTableSeq));
    }
}
