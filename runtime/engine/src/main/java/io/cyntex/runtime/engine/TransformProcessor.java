package io.cyntex.runtime.engine;

import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.Traversers;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import io.cyntex.core.event.Envelope;
import io.cyntex.spi.transform.TransformPort;
import java.util.Objects;

/**
 * Wraps a stateless {@link TransformPort} into a Jet processor: for each inbound {@link Envelope} it
 * emits every envelope the port returns — the one event a map keeps, none for a filtered drop, or
 * the several of a fan-out. One adapter serves the whole stateless family (map / filter / a scripted
 * row transform); each is only a different pure function behind the same seam.
 *
 * <p>Emit-side backpressure is the adapter's concern, not the port's: the flat mapper resumes a
 * partially emitted event when the outbox is full, so the port stays a pure function that does not
 * pace itself.
 */
public final class TransformProcessor extends AbstractProcessor {

    private final FlatMapper<Envelope, Envelope> flatMapper;

    public TransformProcessor(TransformPort port) {
        Objects.requireNonNull(port, "port");
        this.flatMapper = flatMapper(event -> Traversers.traverseIterable(port.transform(event)));
    }

    /**
     * A meta-supplier for a DAG vertex that runs this adapter over the port the factory builds. The
     * factory (not a prebuilt port) is what the DAG carries, so the port is constructed on the member
     * that runs the vertex.
     */
    public static ProcessorMetaSupplier metaSupplier(SupplierEx<? extends TransformPort> portFactory) {
        Objects.requireNonNull(portFactory, "portFactory");
        SupplierEx<Processor> supplier = () -> new TransformProcessor(portFactory.get());
        return ProcessorMetaSupplier.of(supplier);
    }

    @Override
    protected boolean tryProcess(int ordinal, Object item) {
        return flatMapper.tryProcess((Envelope) item);
    }
}
