package io.cyntex.runtime.engine;

import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Inbox;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import io.cyntex.core.event.Envelope;
import io.cyntex.spi.sink.SinkWriter;
import io.cyntex.spi.sink.WriteResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Drives a {@link SinkWriter} from a Jet vertex: it batches inbound events, keeps a bounded number of
 * writes in flight, and completes only once every write has settled. The write side of the contract
 * hands pacing to the runtime, and this is where it lives — batching, the in-flight bound, and the
 * backpressure it produces are all here, while the writer stays a pure delivery contract. One adapter
 * serves every sink; the write mode and ddl policy fold into the writer the factory opens, not here.
 *
 * <p>Backpressure is by refusal: when the in-flight bound is reached the processor stops draining its
 * inbox, so Jet holds the upstream back until an outstanding write settles and frees a slot. A batch's
 * events are handed to the writer and never touched again, honouring the writer's ownership window.
 *
 * <p>The vertex runs at total parallelism one and, by default, keeps a single write in flight: one
 * {@code serve.sync} is one external target, and applying one batch to completion before the next is
 * issued is what keeps a key's change events in their arrival order. Two batches that straddle a key
 * (an insert last in one, its update first in the next) would otherwise be free to apply out of order,
 * since the writer runs them off the caller's thread with no ordering of its own — the contract hands
 * in-flight ordering to the runtime, and this is where the runtime keeps it. Raising the in-flight
 * bound pipelines writes for throughput and is therefore only for a sink whose target applies writes
 * order-independently or is append-only; it is not the default. Snapshotting is not implemented: the
 * durable offset is the source's, not Jet's, so a restart replays from the source rather than
 * resuming a sink snapshot.
 */
public final class SinkProcessor extends AbstractProcessor {

    // One write in flight by default: a batch is applied to completion before the next is issued, so a
    // key's events can never be applied out of their arrival order. Raising this pipelines writes and
    // is only safe for an order-independent or append-only target.
    private static final int DEFAULT_MAX_IN_FLIGHT = 1;
    private static final int DEFAULT_MAX_BATCH_SIZE = 1024;

    private final SinkWriter writer;
    private final SinkAck sinkAck;
    private final Comparator<String> positionOrder;
    private final int maxInFlight;
    private final int maxBatchSize;
    private final List<InFlightBatch> inFlight = new ArrayList<>();
    // Per chain (its src stream): the highest position that has settled but is not yet proven closed. The
    // watermark lags this by one position, so a fan-out's every output is in before the position is acked.
    private final Map<String, String> openPosByChain = new HashMap<>();
    private boolean closed;

    /** No sink-ack watermark: the order-independent or append-only path (any in-flight bound is allowed). */
    public SinkProcessor(SinkWriter writer, int maxInFlight, int maxBatchSize) {
        this(writer, null, null, maxInFlight, maxBatchSize);
    }

    public SinkProcessor(SinkWriter writer, SinkAck sinkAck, Comparator<String> positionOrder,
            int maxInFlight, int maxBatchSize) {
        this.writer = Objects.requireNonNull(writer, "writer");
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be at least 1: " + maxInFlight);
        }
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be at least 1: " + maxBatchSize);
        }
        if (sinkAck != null) {
            Objects.requireNonNull(positionOrder, "positionOrder");
            if (maxInFlight != 1) {
                throw new IllegalArgumentException(
                        "a sink-ack watermark requires maxInFlight == 1 so batches settle in order; got "
                                + maxInFlight);
            }
        }
        this.sinkAck = sinkAck;
        this.positionOrder = positionOrder;
        this.maxInFlight = maxInFlight;
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * A meta-supplier for a sink vertex that drives the writer the factory opens. The factory (not a
     * prebuilt writer) is what the DAG carries, so the writer is opened on the member that runs the
     * vertex. The vertex is pinned to total parallelism one.
     */
    public static ProcessorMetaSupplier metaSupplier(SupplierEx<? extends SinkWriter> writerFactory) {
        Objects.requireNonNull(writerFactory, "writerFactory");
        SupplierEx<Processor> supplier =
                () -> new SinkProcessor(writerFactory.get(), DEFAULT_MAX_IN_FLIGHT, DEFAULT_MAX_BATCH_SIZE);
        return ProcessorMetaSupplier.forceTotalParallelismOne(ProcessorSupplier.of(supplier));
    }

    @Override
    public void process(int ordinal, Inbox inbox) {
        reapSettled();
        while (!inbox.isEmpty() && inFlight.size() < maxInFlight) {
            List<Envelope> batch = new ArrayList<>();
            while (batch.size() < maxBatchSize && !inbox.isEmpty()) {
                batch.add((Envelope) inbox.poll());
            }
            inFlight.add(new InFlightBatch(
                    writer.write(batch).toCompletableFuture(), lastPositionByChain(batch)));
        }
        // A saturated in-flight set leaves the rest of the inbox unread; Jet backpressures upstream
        // until reapSettled frees a slot on a later call.
    }

    @Override
    public boolean complete() {
        reapSettled();
        return inFlight.isEmpty();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        writer.close();
    }

    /** Removes every settled write, surfacing the cause of a failed one so it fails the job. */
    private void reapSettled() {
        inFlight.removeIf(batch -> {
            if (!batch.future().isDone()) {
                return false;
            }
            settle(batch.future()); // throws on a failed write, before any watermark advances
            advanceWatermarks(batch);
            return true;
        });
    }

    /**
     * The last source position each chain contributes to this batch, empty when no watermark is tracked.
     * Under the single-in-flight, order-preserving contract a chain's last event in the batch is its
     * highest position there; an event with no position (a snapshot or synthetic event) does not take part.
     */
    private Map<String, String> lastPositionByChain(List<Envelope> batch) {
        if (sinkAck == null) {
            return Map.of();
        }
        Map<String, String> last = new LinkedHashMap<>();
        for (Envelope event : batch) {
            if (event.srcPos() != null) {
                last.put(event.src(), event.srcPos());
            }
        }
        return last;
    }

    /**
     * Advances each chain's watermark to its contiguous acked prefix. A settled batch proves every event
     * at or below its positions is durably written, but a position stays open until a strictly higher one
     * settles — a fan-out could still place more of it in a later batch. When a higher position closes the
     * open one, the old open position is a complete acked prefix and is acked exactly once, monotonically.
     */
    private void advanceWatermarks(InFlightBatch batch) {
        if (sinkAck == null) {
            return;
        }
        batch.lastPositionByChain().forEach((chain, batchLast) -> {
            String open = openPosByChain.get(chain);
            if (open == null) {
                openPosByChain.put(chain, batchLast);
            } else if (positionOrder.compare(batchLast, open) > 0) {
                sinkAck.advance(chain, open);
                openPosByChain.put(chain, batchLast);
            }
            // batchLast <= open: the same open position (a fan-out continuation), or an out-of-order
            // arrival if a precondition were violated - either way do not advance and do not regress.
        });
    }

    /**
     * Settles one completed write. A failed write is a user-diagnosable delivery error the writer
     * already raised (coded, for a real connector); the cause is rethrown as-is so it fails the job
     * unwrapped rather than buried in a {@link CompletionException}.
     */
    private static void settle(CompletableFuture<WriteResult> future) {
        try {
            future.join();
        } catch (CompletionException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw wrapper;
        }
    }

    /** One outstanding write and the last source position each chain contributed to its batch. */
    private record InFlightBatch(
            CompletableFuture<WriteResult> future, Map<String, String> lastPositionByChain) {
    }
}
