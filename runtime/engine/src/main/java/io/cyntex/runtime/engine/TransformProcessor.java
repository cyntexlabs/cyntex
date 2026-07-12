package io.cyntex.runtime.engine;

import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.Traversers;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import io.cyntex.core.event.Envelope;
import io.cyntex.spi.transform.TransformPort;
import java.util.Objects;

/**
 * Wraps a stateless {@link TransformPort} into a Jet processor: for each inbound {@link Envelope} it
 * emits every envelope the port returns — the one event a map keeps, none for a filtered drop, or
 * the several of a fan-out. One adapter serves the whole stateless family (map / filter / a scripted
 * row transform); each is only a different pure function behind the same seam.
 *
 * <p>Carrying the source position across the transform is the adapter's concern, not the port's: the
 * port is a pure function that never sets a position, so the adapter stamps the inbound event's
 * position onto every event the port returns. A fan-out's several outputs all share the one inbound
 * position; its completion is the sink's concern (every output must settle before the position is
 * acked). An inbound event with no position stamps none.
 *
 * <p>Emit-side backpressure is the adapter's concern too: the flat mapper resumes a partially emitted
 * event when the outbox is full, so the port stays a pure function that does not pace itself.
 *
 * <p>The vertex runs at total parallelism one: a sink downstream acks an ordered position stream, and
 * a parallelism-greater-than-one transform would re-lane events and break that order.
 */
public final class TransformProcessor extends AbstractProcessor {

    private final FlatMapper<Envelope, Envelope> flatMapper;

    public TransformProcessor(TransformPort port) {
        Objects.requireNonNull(port, "port");
        this.flatMapper = flatMapper(event ->
                Traversers.traverseIterable(port.transform(event))
                        .map(out -> out.withSrcPos(event.srcPos())));
    }

    /**
     * A meta-supplier for a DAG vertex that runs this adapter over the port the factory builds. The
     * factory (not a prebuilt port) is what the DAG carries, so the port is constructed on the member
     * that runs the vertex. The vertex is pinned to total parallelism one so it preserves the source
     * position order the sink acks.
     */
    public static ProcessorMetaSupplier metaSupplier(SupplierEx<? extends TransformPort> portFactory) {
        Objects.requireNonNull(portFactory, "portFactory");
        SupplierEx<Processor> supplier = () -> new TransformProcessor(portFactory.get());
        return ProcessorMetaSupplier.forceTotalParallelismOne(ProcessorSupplier.of(supplier));
    }

    @Override
    protected boolean tryProcess(int ordinal, Object item) {
        return flatMapper.tryProcess((Envelope) item);
    }
}
