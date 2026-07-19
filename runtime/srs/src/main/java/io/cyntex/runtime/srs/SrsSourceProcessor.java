package io.cyntex.runtime.srs;

import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.ringbuffer.Ringbuffer;
import io.cyntex.core.event.Envelope;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * The core-API self-built source over one per-table change ring: a Jet processor with no inbound edge that
 * emits this ring's buffered snapshot rows first, then tails the ring in sequence order emitting each change
 * already projected to the {@link Envelope} currency, the source vertex the engine's DAG builder wires. It is
 * the core-API sibling of {@link SrsRingSource} (the pipeline-API stream source over raw items): the DAG
 * builder speaks in processor suppliers, and the source position must enter the envelope at the source, so
 * the projection lives here rather than in a later stage.
 *
 * <p>Snapshot rows and cdc changes flow through this one ordered source: the snapshot rows a member-side
 * {@link SnapshotBuffer} holds for this ring are drained once at init and emitted ahead of any cdc change, so
 * the older snapshot value can never land at the sink after a newer change of the same key. A member with no
 * buffer bound, or a ring with none buffered (a cdc-only read), is a pure ring tail.
 *
 * <p>Non-cooperative, exactly as Jet's own SourceBuilder-built source is: it runs on its own thread and backs
 * off between empty fills, so an idle ring never spins a shared cooperative thread. It is not fault-tolerant -
 * it keeps no snapshot and its read position never enters Jet state; on an L1 restart the ring is re-mined and
 * replayed from the durable source offset. The ring and the read-cursor sink are resolved on the member the
 * processor runs on, so nothing but serializable coordinates crosses the wire.
 */
public final class SrsSourceProcessor extends AbstractProcessor {

    /** The most changes one fill drains before yielding - a bounded batch that lets Jet pace the source. */
    private static final int FILL_BATCH = 256;

    private final String ringName;
    private final String src;
    private final StartFrom start;
    private final SrsReadCursorPublisherFactory publisherFactory;
    private final ArrayDeque<Envelope> pending = new ArrayDeque<>();
    private SrsRingReader reader;

    private SrsSourceProcessor(String ringName, String src, StartFrom start,
            SrsReadCursorPublisherFactory publisherFactory) {
        this.ringName = ringName;
        this.src = src;
        this.start = start;
        this.publisherFactory = publisherFactory;
    }

    @Override
    protected void init(Context context) {
        // Seed the emit buffer with this ring's snapshot rows before opening the ring reader, so the source
        // emits every snapshot row (op r, no source position) ahead of the first cdc change -- the ordering
        // that keeps a stale snapshot from landing at the sink after a newer change of the same key. A member
        // with no snapshot buffer bound, or a ring with none buffered (a cdc-only read), seeds nothing and the
        // source is a pure ring tail. The rows are drained once here, in buffered order, and preserved as-is:
        // their null source position is what the sink-ack watermark skips, so only cdc positions advance it.
        // This is a streaming source: it assumes a cdc tail follows the snapshot. A snapshot-only read (no
        // tail, a bounded source that emits the buffer then completes rather than tailing an empty ring) is a
        // later increment; it is not driven through this vertex yet.
        Object bound = context.hazelcastInstance().getUserContext().get(SnapshotBuffer.USER_CONTEXT_KEY);
        if (bound instanceof SnapshotBuffer buffer) {
            pending.addAll(buffer.drain(ringName));
        }
        Ringbuffer<SrsItem> rb = context.hazelcastInstance().getRingbuffer(ringName);
        SrsRingbuffer ring = new SrsRingbuffer(rb);
        reader = SrsRingReader.from(ring, start, publisherFactory.resolve(context.hazelcastInstance()));
    }

    @Override
    public boolean isCooperative() {
        return false;
    }

    @Override
    public boolean complete() {
        // Emit anything held back by earlier backpressure before reading more: the cursor only advances as
        // fill reads, so a change the outbox refused stays buffered until it is taken, never re-read nor lost.
        if (!emitPending()) {
            return false;
        }
        reader.fill(item -> pending.add(SrsProjection.toEnvelope(item, src)), FILL_BATCH);
        emitPending();
        // A streaming source never completes: on an empty ring it returns having emitted nothing and, being
        // non-cooperative, its worker backs off before the next call rather than spinning.
        return false;
    }

    /** Emits buffered envelopes until the outbox refuses one; true when the buffer is fully drained. */
    private boolean emitPending() {
        while (!pending.isEmpty()) {
            if (!tryEmit(pending.peek())) {
                return false;
            }
            pending.remove();
        }
        return true;
    }

    /**
     * A meta-supplier for a source vertex tailing {@code ringName} from {@code start}, tagging every change
     * with the logical stream name {@code src} and reporting its read cursor through {@code publisherFactory}.
     * The vertex is pinned to total parallelism one: one reader per ring keeps the change stream in order.
     */
    public static ProcessorMetaSupplier metaSupplier(String ringName, String src, StartFrom start,
            SrsReadCursorPublisherFactory publisherFactory) {
        Objects.requireNonNull(ringName, "ringName");
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(publisherFactory, "publisherFactory");
        SupplierEx<Processor> supplier = () -> new SrsSourceProcessor(ringName, src, start, publisherFactory);
        return ProcessorMetaSupplier.forceTotalParallelismOne(ProcessorSupplier.of(supplier));
    }
}
