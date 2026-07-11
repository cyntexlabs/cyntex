package io.cyntex.spi.store;

import java.util.Optional;

/**
 * The durable SRS coordination store: one {@link SrsMeta} record per mining chain — the offset, consumer
 * cursor and schema truth that outlives the in-memory change ring. A pure interface over the store's own
 * value model (rule R2); a store backend persists it, and positions travel as opaque tokens, never as a
 * connector type.
 *
 * <p>{@link #create} seeds a chain's first record, carrying only the pass-through retention config. It is
 * insert-only: it must not overwrite an existing record, because doing so would discard the offset,
 * consumer-cursor and schema history the chain has accumulated. Seeding a chain that already has a record
 * is a caller ordering error; a caller that needs to know can {@link #read} first.
 *
 * <p>The mutators each update one facet of an already-seeded record — the source read offset, one
 * consumer's cursor, the cdc start position, or the schema history. A mutate on a chain that has not been
 * seeded is a caller ordering error, surfaced bare (an {@code IllegalStateException}), not laundered into
 * a coded diagnostic that would hide the defect. The durable-frontier bound on a source-read-offset
 * advance (an advance must not pass the slowest consumer's acked position) is the caller's concern; this
 * store persists the value the caller resolved.
 */
public interface SrsMetaStore {

    /** Returns the meta record for a mining chain, or empty if the chain has not been seeded. */
    Optional<SrsMeta> read(String miningChainId);

    /**
     * Seeds a mining chain's first record — no offsets, no consumers, no schema history, carrying only
     * the pass-through {@code retention} config (which may be absent). Insert-only: it must not overwrite
     * an existing record (which would discard the chain's accumulated offset / cursor / schema truth).
     */
    void create(String miningChainId, String retention);

    /**
     * Sets the chain's source read offset to {@code sourceReadOffset}, an opaque source capture
     * watermark. The durable-frontier bound is the caller's concern; this persists the resolved value.
     * A mutate on an unseeded chain is a caller ordering error.
     */
    void advanceSourceReadOffset(String miningChainId, String sourceReadOffset);

    /**
     * Inserts or replaces one consumer pipeline's cursor on the chain, keyed by its pipeline id. A
     * mutate on an unseeded chain is a caller ordering error.
     */
    void upsertConsumerOffset(String miningChainId, ConsumerOffset offset);

    /**
     * Advances one consumer pipeline's read cursor into one table's change ring — a scoped set of that
     * consumer's {@code perTableSeq} entry for the table alone. It touches only the read cursor, so a
     * reader advancing here never clobbers the {@code sinkAckedSrcpos} the pipeline's sink writes to the
     * same consumer record: the read cursor and the sink-ack are independent writers of one consumer, of
     * different lifetime. It creates the consumer entry when the pipeline has none yet, so a reader may
     * advance before the sink first acks. A mutate on an unseeded chain is a caller ordering error.
     */
    void advanceConsumerReadSeq(String miningChainId, String pipelineId, String table, long lastReadSeq);

    /**
     * Sets the chain's cdc start position — the opaque position the cdc tail starts from, recorded at
     * the snapshot-to-cdc seam. A mutate on an unseeded chain is a caller ordering error.
     */
    void setCdcStartPosition(String miningChainId, String cdcStartPosition);

    /**
     * Appends a version to the chain's append-only schema history. A mutate on an unseeded chain is a
     * caller ordering error.
     */
    void appendSchemaVersion(String miningChainId, SchemaVersion version);
}
