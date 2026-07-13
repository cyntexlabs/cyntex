package io.cyntex.runtime.srs;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * The headroom guard on writes into one per-table change ring. A cdc write is admitted only when it would
 * not overwrite a change the slowest consumer has not read yet; otherwise it is backpressured — refused,
 * so the source read pauses until a consumer advances and frees a slot, never silently dropping an unread
 * change. This is the application-layer overflow control the ring itself does not do: the ring only writes
 * and reports its bounds.
 *
 * <p>The precheck compares the sequence the next write would take, {@code tailSeq + 1}, against the
 * slowest consumer's read cursor — a write is refused when {@code (tailSeq + 1) - minConsumerReadSeq >
 * capacity}, i.e. it would evict a sequence no consumer has read. {@code minConsumerReadSeq} is the
 * minimum last-read sequence across the ring's consumers ({@code -1} when a consumer has read nothing);
 * pass {@link Long#MAX_VALUE} when no consumer constrains the ring.
 */
public final class SrsWriteGate {

    private final SrsRingbuffer ring;

    public SrsWriteGate(SrsRingbuffer ring) {
        this.ring = Objects.requireNonNull(ring, "ring");
    }

    /**
     * Appends the change and returns the sequence the ring assigned it, or empty when the write is
     * backpressured — it would overwrite a change the slowest consumer has not read, so nothing is
     * written and the caller pauses the source read until a consumer advances.
     */
    public OptionalLong append(SrsItem item, long minConsumerReadSeq) {
        Objects.requireNonNull(item, "item");
        if (!hasHeadroom(ring.tailSequence(), ring.capacity(), minConsumerReadSeq)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(ring.append(item));
    }

    /**
     * Whether a write is admitted: the next write takes {@code tailSeq + 1}, and it must not evict a
     * sequence the slowest consumer has not read — refused when {@code (tailSeq + 1) - minConsumerReadSeq
     * > capacity}. The subtraction form stays correct for the {@link Long#MAX_VALUE} unconstrained
     * sentinel, driving the difference far negative rather than overflowing into a false refusal.
     */
    static boolean hasHeadroom(long tailSeq, long capacity, long minConsumerReadSeq) {
        return (tailSeq + 1) - minConsumerReadSeq <= capacity;
    }
}
