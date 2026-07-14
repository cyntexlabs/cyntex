package io.cyntex.runtime.srs;

import io.cyntex.core.event.Envelope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * A member-local buffer that carries one source's bounded snapshot rows from the capture side to the source
 * vertex, keyed by the per-table change ring the source reads. The capture side appends every snapshot row
 * for a ring; the source vertex drains them once and emits them ahead of the ring's cdc tail. That ordering
 * is the data-consistency guarantee: a snapshot row (the older value) must reach the sink before any cdc
 * change of the same key, or a stale snapshot would overwrite a newer change.
 *
 * <p>It is plain member-local state, never a distributed structure: there is one embedded member per process,
 * the capture side that writes and the source vertex that reads run in that one process, and a snapshot row
 * carries no source position, so it never needs to survive a member restart the way the cdc ring's durable
 * source offset does. Both sides may touch it concurrently, so it is backed by concurrent maps and queues.
 *
 * <p>A drain is once-consumed: it removes a ring's rows and returns them, so a second drain of the same ring
 * yields nothing. That matches how the source vertex resolves the buffer exactly once when it initializes.
 */
public final class SnapshotBuffer {

    /**
     * The member user-context key under which the buffer is bound, so a source vertex can resolve it
     * member-side by the ring name it already carries. The assembly layer binds the buffer under this key
     * when it makes the member SRS-capable; a member with no buffer bound emits no snapshot ahead of the tail.
     */
    public static final String USER_CONTEXT_KEY = "cyntex.srs.snapshot-buffer";

    private final ConcurrentMap<String, Queue<Envelope>> byRing = new ConcurrentHashMap<>();

    /** Appends one snapshot row to {@code ringName}'s buffer, preserving append order within the ring. */
    public void append(String ringName, Envelope row) {
        Objects.requireNonNull(ringName, "ringName");
        Objects.requireNonNull(row, "row");
        byRing.computeIfAbsent(ringName, ignored -> new ConcurrentLinkedQueue<>()).add(row);
    }

    /**
     * Removes and returns {@code ringName}'s buffered snapshot rows in append order, or an empty list when
     * the ring was never appended to or has already been drained. Once-consumed: the ring's buffer is
     * cleared, so a later drain returns nothing.
     */
    public List<Envelope> drain(String ringName) {
        Objects.requireNonNull(ringName, "ringName");
        Queue<Envelope> rows = byRing.remove(ringName);
        return rows == null ? List.of() : new ArrayList<>(rows);
    }
}
