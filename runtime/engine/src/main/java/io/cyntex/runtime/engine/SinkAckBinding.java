package io.cyntex.runtime.engine;

import com.hazelcast.function.ComparatorEx;
import java.util.Objects;

/**
 * The sink-side ack wiring the DAG builder needs to close the durable frontier loop, supplied by the
 * assembly root. It pairs the two things a sink vertex needs to advance a durable sink-acked watermark:
 * the {@link SinkAckFactory} that resolves the durable store member-side, and the {@code positionOrder}
 * over the opaque source-position tokens. One binding serves every sink vertex of one pipeline — all its
 * sinks ack the same consumer through the same coordinates.
 *
 * <p>The position order MUST rank the same token format the capture watermark emits, so the sink's
 * contiguous-acked-prefix advance and the source-read frontier speak one order; a mismatch would ack
 * positions the frontier cannot compare. When this binding is absent the builder wires the no-ack sink
 * instead, so an ack-less run (no durable store bound) still builds and runs.
 */
public record SinkAckBinding(SinkAckFactory ackFactory, ComparatorEx<String> positionOrder) {

    public SinkAckBinding {
        Objects.requireNonNull(ackFactory, "ackFactory");
        Objects.requireNonNull(positionOrder, "positionOrder");
    }
}
