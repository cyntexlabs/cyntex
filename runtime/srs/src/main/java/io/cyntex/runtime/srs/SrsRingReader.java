package io.cyntex.runtime.srs;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * One consumer's reader over a per-table change ring. It tails the ring from a run-local cursor,
 * emitting each change once in sequence order and advancing the cursor as it goes. A fill drains a
 * bounded batch — from the cursor up to the ring tail, capped at the caller's max — so the reader yields
 * to the downstream between batches (Jet backpressure) and never blocks for a change that has not been
 * written yet.
 *
 * <p>The cursor is run-local and deliberately not fault-tolerant: it is never written into a Jet
 * snapshot, so the source's position never lives in execution state. On an L1 restart the volatile ring
 * is gone; recovery re-mines the ring from the durable source read offset and this reader replays it from
 * the start. The offset truth stays in the durable coordination store, never in Jet.
 */
public final class SrsRingReader {

    private final SrsRingbuffer ring;
    private long cursor;

    /**
     * A reader that begins at {@code startSeq} — the next sequence it will read. At L1 a fresh reader
     * starts at the ring head to replay every buffered change.
     */
    public SrsRingReader(SrsRingbuffer ring, long startSeq) {
        this.ring = Objects.requireNonNull(ring, "ring");
        this.cursor = startSeq;
    }

    /**
     * A reader positioned by a {@code start} point resolved against the ring: {@code earliest} at the head
     * (replay everything buffered), {@code latest} just past the tail (only changes appended from now on),
     * and an instant at the first change whose event time is at or after it. An instant older than every
     * buffered change clamps to the head — the earliest still available — since the bounded ring cannot
     * replay history it no longer holds; an instant newer than every buffered change starts past the tail.
     */
    public static SrsRingReader from(SrsRingbuffer ring, StartFrom start) {
        Objects.requireNonNull(ring, "ring");
        Objects.requireNonNull(start, "start");
        return new SrsRingReader(ring, resolveStartSeq(ring, start));
    }

    private static long resolveStartSeq(SrsRingbuffer ring, StartFrom start) {
        return switch (start) {
            case StartFrom.Earliest ignored -> ring.headSequence();
            case StartFrom.Latest ignored -> ring.tailSequence() + 1;
            case StartFrom.At at -> firstSeqAtOrAfter(ring, at.instant().toEpochMilli());
        };
    }

    /** The first sequence whose change is at or after {@code targetMillis}, or just past the tail if none is. */
    private static long firstSeqAtOrAfter(SrsRingbuffer ring, long targetMillis) {
        long tail = ring.tailSequence();
        for (long seq = ring.headSequence(); seq <= tail; seq++) {
            if (ring.readOne(seq).ts() >= targetMillis) {
                return seq;
            }
        }
        return tail + 1;
    }

    /**
     * Drains up to {@code max} changes from the cursor to the ring tail, passing each to {@code out} and
     * advancing the cursor past it, and returns how many were emitted. Bounded by {@code max} and by the
     * tail, so it respects the downstream's pull and returns promptly when the ring holds nothing new.
     */
    public int fill(Consumer<SrsItem> out, int max) {
        Objects.requireNonNull(out, "out");
        long tail = ring.tailSequence();
        int emitted = 0;
        while (cursor <= tail && emitted < max) {
            out.accept(ring.readOne(cursor));
            cursor++;
            emitted++;
        }
        return emitted;
    }
}
