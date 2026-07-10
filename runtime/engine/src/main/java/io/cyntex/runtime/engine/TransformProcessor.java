package io.cyntex.runtime.engine;

import com.hazelcast.jet.Traversers;
import com.hazelcast.jet.core.AbstractProcessor;
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

    @Override
    protected boolean tryProcess(int ordinal, Object item) {
        return flatMapper.tryProcess((Envelope) item);
    }
}
