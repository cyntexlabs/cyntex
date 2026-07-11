package io.cyntex.runtime.srs;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.ringbuffer.Ringbuffer;
import io.cyntex.core.event.Envelope;
import io.cyntex.core.event.Op;
import io.cyntex.spi.capture.CaptureBatch;
import io.cyntex.spi.capture.CaptureConfig;
import io.cyntex.spi.capture.CaptureListener;
import io.cyntex.spi.capture.CapturePort;
import io.cyntex.spi.capture.ConnectionReport;
import io.cyntex.spi.capture.DiscoveredSchema;
import io.cyntex.spi.capture.SourcePosition;
import io.cyntex.spi.capture.Subscription;
import io.cyntex.spi.store.ConsumerOffset;
import io.cyntex.spi.store.SchemaVersion;
import io.cyntex.spi.store.SrsMeta;
import io.cyntex.spi.store.SrsMetaStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cdc phase drives an unbounded change stream through the headroom gate into the per-table change
 * ring: each change event (ops {@code i} / {@code u} / {@code d} / {@code ddl}) is projected to a ring
 * item, admitted only while a consumer's unread change is not at risk, and the source read offset is
 * advanced — clamped to the slowest consumer's sink-acked position — as writes land. The ring runs over a
 * single embedded Hazelcast member sized to the L1 hot-buffer shape (capacity 8), matching the write-gate
 * tests.
 *
 * <p>The per-event source position is threaded here at the cdc seam: the event envelope carries no
 * position slot, so at L1 a mock monotonic watermark supplies each change its opaque position (D10); real
 * per-event position threading from the connector is a later concern.
 */
class CdcPhaseTest {

    private static HazelcastInstance hz;

    /** Orders the mock positions {@code w1 < w2 < w3 < ...} by their numeric suffix; a source position is never ordered lexically. */
    private static final Comparator<String> NUMERIC_ORDER = Comparator.comparingInt(p -> Integer.parseInt(p.substring(1)));

    @BeforeAll
    static void startMember() {
        Config config = new Config();
        // Isolated, structurally undiscoverable single member -- never merge with anything on the LAN.
        config.setClusterName("srs-cdc-test-" + System.nanoTime());
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getJetConfig().setEnabled(false);
        config.addRingBufferConfig(new RingbufferConfig("srs.*")
                .setCapacity(8)
                .setInMemoryFormat(InMemoryFormat.OBJECT)
                .setTimeToLiveSeconds(0)
                .setBackupCount(0));
        config.getSerializationConfig().addSerializerConfig(
                new SerializerConfig().setImplementation(new SrsItemSerializer()).setTypeClass(SrsItem.class));
        hz = Hazelcast.newHazelcastInstance(config);
    }

    @AfterAll
    static void stopMember() {
        if (hz != null) {
            hz.shutdown();
        }
    }

    private static CaptureConfig config() {
        return new CaptureConfig("mysql", Map.of(), List.of("orders"));
    }

    /** A mock cdc watermark: a monotonic source-position generator (w1, w2, ...) standing in for the connector-defined per-event position. */
    private static Supplier<SourcePosition> monotonicWatermark() {
        AtomicLong n = new AtomicLong();
        return () -> new SourcePosition("w" + n.incrementAndGet());
    }

    /** A change-ring item used to fill the ring in the backpressure test; a plain insert at the given position. */
    private static SrsItem cdcItem(String token) {
        return new SrsItem(new SourcePosition(token), Op.INSERT, 1L, null, Map.of("id", 1), 0L);
    }

    @Test
    void projectsEachCdcChangeToTheRingInOrder() throws Exception {
        Ringbuffer<SrsItem> ring = hz.getRingbuffer("srs.chain.order");
        SrsWriteGate gate = new SrsWriteGate(new SrsRingbuffer(ring));
        CdcChain chain = new CdcChain(gate, new RecordingMeta(), "chain", monotonicWatermark(), NUMERIC_ORDER, 0L);
        FakeCdcPort port = new FakeCdcPort(List.of(
                Envelope.insert(1, "orders", Map.of("id", 1), Map.of()),
                Envelope.update(2, "orders", Map.of("id", 1), Map.of("id", 1, "n", 9), Map.of()),
                Envelope.delete(3, "orders", Map.of("id", 1), Map.of())));

        CdcPhase.run(port, config(), chain, () -> Long.MAX_VALUE, List::of);

        assertThat(ring.tailSequence()).isEqualTo(2L);
        SrsItem first = ring.readOne(0);
        SrsItem second = ring.readOne(1);
        SrsItem third = ring.readOne(2);
        assertThat(first.op()).isEqualTo(Op.INSERT);
        assertThat(first.srcPos()).isEqualTo(new SourcePosition("w1"));
        assertThat(first.after()).containsEntry("id", 1);
        assertThat(second.op()).isEqualTo(Op.UPDATE);
        assertThat(second.srcPos()).isEqualTo(new SourcePosition("w2"));
        assertThat(second.before()).containsEntry("id", 1);
        assertThat(third.op()).isEqualTo(Op.DELETE);
        assertThat(third.srcPos()).isEqualTo(new SourcePosition("w3"));
        assertThat(third.before()).containsEntry("id", 1);
    }

    @Test
    void advancesTheSourceReadOffsetClampedToTheSlowestSinkAckedPosition() {
        SrsWriteGate gate = new SrsWriteGate(new SrsRingbuffer(hz.getRingbuffer("srs.chain.offset")));
        RecordingMeta meta = new RecordingMeta();
        // One consumer has durably acked its sink up to w2; the persisted read offset must never pass it.
        List<ConsumerOffset> consumers = List.of(new ConsumerOffset("p1", Map.of("orders", 9L), "w2"));
        CdcChain chain = new CdcChain(gate, meta, "chain", monotonicWatermark(), NUMERIC_ORDER, 0L);
        FakeCdcPort port = new FakeCdcPort(List.of(
                Envelope.insert(1, "orders", Map.of("id", 1), Map.of()),
                Envelope.insert(2, "orders", Map.of("id", 2), Map.of()),
                Envelope.insert(3, "orders", Map.of("id", 3), Map.of())));

        CdcPhase.run(port, config(), chain, () -> Long.MAX_VALUE, () -> consumers);

        // Written at w1, w2, w3; each advance is clamped to the slowest sink-acked position w2, so the
        // persisted offset never passes a change no consumer has durably landed.
        assertThat(meta.advances).containsExactly("w1", "w2", "w2");
    }

    @Test
    void backpressuresARefusedWriteAndRetriesRatherThanDropIt() throws Exception {
        Ringbuffer<SrsItem> raw = hz.getRingbuffer("srs.chain.backpressure");
        SrsWriteGate gate = new SrsWriteGate(new SrsRingbuffer(raw));
        // Fill the ring to capacity with changes no consumer has read (min read seq -1).
        for (int i = 0; i < 8; i++) {
            assertThat(gate.append(cdcItem("f" + i), -1L)).isPresent();
        }
        // The slowest consumer has read nothing on the first poll (the write is refused), then advances to
        // seq 0 on the next -- modeling the source read pausing until a consumer frees a slot.
        AtomicLong polls = new AtomicLong();
        LongSupplier minRead = () -> polls.getAndIncrement() == 0 ? -1L : 0L;
        CdcChain chain = new CdcChain(gate, new RecordingMeta(), "chain", monotonicWatermark(), NUMERIC_ORDER, 0L);
        FakeCdcPort port = new FakeCdcPort(List.of(Envelope.insert(9, "orders", Map.of("id", 9), Map.of())));

        CdcPhase.run(port, config(), chain, minRead, List::of);

        // The change is not dropped: it lands at seq 8 once headroom frees, and the write was retried
        // (polled more than once) rather than silently overwriting the still-unread seq 0.
        assertThat(raw.tailSequence()).isEqualTo(8L);
        assertThat(raw.readOne(8).srcPos()).isEqualTo(new SourcePosition("w1"));
        assertThat(polls.get()).isGreaterThan(1L);
    }

    @Test
    void stopsTheStreamThroughTheReturnedSubscription() {
        SrsWriteGate gate = new SrsWriteGate(new SrsRingbuffer(hz.getRingbuffer("srs.chain.sub")));
        CdcChain chain = new CdcChain(gate, new RecordingMeta(), "chain", monotonicWatermark(), NUMERIC_ORDER, 0L);
        FakeCdcPort port = new FakeCdcPort(List.of());

        Subscription sub = CdcPhase.run(port, config(), chain, () -> Long.MAX_VALUE, List::of);
        sub.close();

        // The phase hands back the port's own subscription; closing it stops the stream.
        assertThat(port.closed).isTrue();
    }

    @Test
    void rejectsIncompleteWiringBeforeStartingTheStream() {
        SrsWriteGate gate = new SrsWriteGate(new SrsRingbuffer(hz.getRingbuffer("srs.chain.guard")));
        CdcChain chain = new CdcChain(gate, new RecordingMeta(), "chain", monotonicWatermark(), NUMERIC_ORDER, 0L);
        FakeCdcPort port = new FakeCdcPort(List.of(Envelope.insert(1, "orders", Map.of("id", 1), Map.of())));

        assertThatThrownBy(() -> CdcPhase.run(port, config(), chain, null, List::of))
                .isInstanceOf(NullPointerException.class);

        // Args are validated up front: the stream is never started when the wiring is incomplete.
        assertThat(port.subscribed).isFalse();
    }

    /** A cdc port that drives a fixed list of change events into the listener when the stream starts. */
    private static final class FakeCdcPort implements CapturePort {
        private final List<Envelope> events;
        boolean subscribed;
        boolean closed;

        FakeCdcPort(List<Envelope> events) {
            this.events = events;
        }

        @Override
        public Subscription cdc(CaptureConfig config, CaptureListener listener) {
            subscribed = true;
            for (Envelope e : events) {
                listener.onEvent(e);
            }
            return () -> closed = true;
        }

        @Override
        public CaptureBatch snapshot(CaptureConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConnectionReport testConnection(CaptureConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DiscoveredSchema discoverSchema(CaptureConfig config) {
            throw new UnsupportedOperationException();
        }
    }

    /** A meta store that records the sequence of source-read-offset advances; the other facets are unused here. */
    private static final class RecordingMeta implements SrsMetaStore {
        final List<String> advances = new ArrayList<>();

        @Override
        public void advanceSourceReadOffset(String miningChainId, String sourceReadOffset) {
            advances.add(sourceReadOffset);
        }

        @Override
        public Optional<SrsMeta> read(String miningChainId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void create(String miningChainId, String retention) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void upsertConsumerOffset(String miningChainId, ConsumerOffset offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setCdcStartPosition(String miningChainId, String cdcStartPosition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendSchemaVersion(String miningChainId, SchemaVersion version) {
            throw new UnsupportedOperationException();
        }
    }
}
