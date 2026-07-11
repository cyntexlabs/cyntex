package io.cyntex.runtime.srs;

import com.hazelcast.jet.pipeline.SourceBuilder;
import com.hazelcast.jet.pipeline.StreamSource;
import com.hazelcast.ringbuffer.Ringbuffer;

import java.util.Objects;

/**
 * The self-built Jet source over one per-table change ring. Jet has no native Hazelcast-Ringbuffer
 * source, so this builds one: a stream source whose per-member reader ({@link SrsRingReader}) tails the
 * ring by sequence and feeds each change into the Jet pipeline, bounded per fill so it honours Jet
 * backpressure.
 *
 * <p>The source is deliberately not fault-tolerant — it sets no snapshot or restore function. Its read
 * position never enters a Jet snapshot: the offset truth lives in the durable coordination store, and an
 * L1 restart re-mines the ring and replays it from the head rather than resuming from execution state.
 * The reader resolves the ring from the member it runs on, so nothing but the ring name crosses the wire.
 */
public final class SrsRingSource {

    /** The most changes one fill drains before yielding — a bounded batch that lets Jet pace the source. */
    private static final int FILL_BATCH = 256;

    private SrsRingSource() {
    }

    /** A Jet stream source that tails the named change ring from its head, in sequence order. */
    public static StreamSource<SrsItem> create(String ringName) {
        Objects.requireNonNull(ringName, "ringName");
        return SourceBuilder
                .stream("srs-source-" + ringName, ctx -> {
                    Ringbuffer<SrsItem> rb = ctx.hazelcastInstance().getRingbuffer(ringName);
                    SrsRingbuffer ring = new SrsRingbuffer(rb);
                    return new SrsRingReader(ring, ring.headSequence());
                })
                .fillBufferFn((SrsRingReader reader, SourceBuilder.SourceBuffer<SrsItem> buffer) ->
                        reader.fill(buffer::add, FILL_BATCH))
                .build();
    }
}
