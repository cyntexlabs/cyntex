package io.cyntex.runtime.srs;

import io.cyntex.spi.capture.SourcePosition;
import io.cyntex.spi.store.SrsMetaStore;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The write-side wiring for one mining chain's cdc phase: where change events land (the per-table headroom
 * {@code gate}), where the durable read offset is persisted ({@code meta} under {@code miningChainId}), the
 * {@code watermark} that stamps each change its source position, the connector-defined {@code positionOrder}
 * that ranks opaque positions for the durable-frontier bound, and the {@code schemaVer} in force. An
 * immutable value grouping the stable collaborators the cdc listener closes over.
 *
 * <p>The position order is external and connector-defined: source positions are opaque tokens this never
 * parses, and their order is never lexicographic.
 */
public record CdcChain(
        SrsWriteGate gate,
        SrsMetaStore meta,
        String miningChainId,
        Supplier<SourcePosition> watermark,
        Comparator<String> positionOrder,
        long schemaVer) {

    public CdcChain {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(miningChainId, "miningChainId");
        Objects.requireNonNull(watermark, "watermark");
        Objects.requireNonNull(positionOrder, "positionOrder");
    }
}
