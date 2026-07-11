package io.cyntex.spi.store;

import java.util.List;

/**
 * The durable coordination record for one mining chain — the offset and schema truth that outlives the
 * in-memory change ring. A mining chain is the shared cdc capture keyed by physical source coordinates,
 * so one record serves every table and every consumer pipeline on that chain.
 *
 * <p>Fields — {@code miningChainId} (the chain this record is keyed by), {@code sourceReadOffset} (the
 * opaque source capture watermark the chain has read up to; absent until the first cdc read; its
 * durable advance is bounded by the slowest consumer's acked position), {@code consumerOffsets} (one
 * cursor per consumer pipeline), {@code cdcStartPosition} (the opaque position the cdc tail starts from,
 * recorded at the snapshot-to-cdc seam; absent until a snapshot seam or start point resolves it),
 * {@code schemaHistory} (the append-only versioned schema), and {@code retention} (the retention
 * configuration passed through from the source; a config value only — the change ring is bounded by
 * its capacity and backpressure, not trimmed by this).
 *
 * <p>The field set is append-only: a field may be added but never removed or repurposed, so an older
 * reader stays forward-compatible. The lists are unmodifiable defensive copies. A pure value over
 * {@code java..} only (rule R2): positions travel as opaque tokens, never as a connector type.
 */
public record SrsMeta(
        String miningChainId,
        String sourceReadOffset,
        List<ConsumerOffset> consumerOffsets,
        String cdcStartPosition,
        List<SchemaVersion> schemaHistory,
        String retention) {

    public SrsMeta {
        if (miningChainId == null || miningChainId.isBlank()) {
            throw new IllegalArgumentException("srs meta miningChainId must be non-blank");
        }
        if (consumerOffsets == null) {
            throw new IllegalArgumentException("srs meta consumerOffsets must be set");
        }
        if (schemaHistory == null) {
            throw new IllegalArgumentException("srs meta schemaHistory must be set");
        }
        consumerOffsets = List.copyOf(consumerOffsets);
        schemaHistory = List.copyOf(schemaHistory);
    }
}
