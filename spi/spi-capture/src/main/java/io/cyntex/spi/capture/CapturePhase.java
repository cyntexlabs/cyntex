package io.cyntex.spi.capture;

import io.cyntex.core.event.Op;

/**
 * The two phases of a capture. A source is read first in a bounded {@code SNAPSHOT} phase — snapshot
 * reads of the current rows (op {@code r}) — and then, where a change tail is attached, an unbounded
 * {@code CDC} phase of row and schema mutations. The hand-off is snapshot-complete → cdc: the bounded
 * snapshot drains, then the change tail resumes from the position recorded at that seam.
 *
 * <p>The phase an event belongs to follows its op: a snapshot read is the snapshot phase, every change
 * mutation ({@code i} / {@code u} / {@code d} / {@code ddl}) is the cdc phase.
 */
public enum CapturePhase {

    /** The bounded initial read of the current rows; every event is a snapshot read (op {@code r}). */
    SNAPSHOT,

    /** The unbounded change tail; events are row and schema mutations (ops {@code i} / {@code u} / {@code d} / {@code ddl}). */
    CDC;

    /** The phase an event belongs to, from its op: a snapshot read is {@link #SNAPSHOT}, any other op is {@link #CDC}. */
    public static CapturePhase of(Op op) {
        return op == Op.READ ? SNAPSHOT : CDC;
    }
}
