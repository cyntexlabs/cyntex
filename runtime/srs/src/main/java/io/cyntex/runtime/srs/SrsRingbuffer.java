package io.cyntex.runtime.srs;

import com.hazelcast.ringbuffer.Ringbuffer;

import java.util.Objects;

/**
 * The per-table change ring: the in-memory hot buffer holding one mining chain's cdc changes for one
 * table, backed by a Hazelcast Ringbuffer. An append carries a monotonic sequence the ring assigns;
 * consumers track their own read cursor against that sequence.
 *
 * <p>Append is a raw write. Overflow safety — the headroom precheck that refuses a write which would
 * overwrite a change no consumer has read yet, and the backpressure that follows — is layered by the
 * caller, not here: this class only writes and reports the ring's bounds.
 */
public final class SrsRingbuffer {

    private static final String NAME_PREFIX = "srs.";

    private final Ringbuffer<SrsItem> ringbuffer;

    public SrsRingbuffer(Ringbuffer<SrsItem> ringbuffer) {
        this.ringbuffer = Objects.requireNonNull(ringbuffer, "ringbuffer");
    }

    /**
     * The ring name for one table of a mining chain — the per-chain, per-table namespace under which
     * the ring is created and looked up.
     */
    public static String ringName(String miningChainId, String table) {
        if (miningChainId == null || miningChainId.isBlank()) {
            throw new IllegalArgumentException("ring name miningChainId must be non-blank");
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("ring name table must be non-blank");
        }
        return NAME_PREFIX + miningChainId + "." + table;
    }

    /**
     * Appends one change and returns the sequence the ring assigned it. A raw write: the caller guards
     * against overwriting an unread change (headroom precheck + backpressure) before calling this.
     */
    public long append(SrsItem item) {
        Objects.requireNonNull(item, "item");
        return ringbuffer.add(item);
    }

    /** The sequence of the most recent item, or {@code -1} when the ring is empty. */
    public long tailSequence() {
        return ringbuffer.tailSequence();
    }

    /** The fixed capacity of the ring — the bound the headroom precheck measures against. */
    public long capacity() {
        return ringbuffer.capacity();
    }
}
