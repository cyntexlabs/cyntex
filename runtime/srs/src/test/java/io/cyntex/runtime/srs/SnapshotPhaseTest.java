package io.cyntex.runtime.srs;

import io.cyntex.core.event.Envelope;
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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The snapshot phase drains the bounded snapshot read (op {@code r}) straight to the downstream stage,
 * recording the cdc-start position at the seam before it drains, and never buffering a snapshot event in
 * the change ring. Its collaborators are faked here: a port that yields a fixed batch, a meta store that
 * records the cdc-start position, and a capturing downstream sink.
 */
class SnapshotPhaseTest {

    private static CaptureConfig config() {
        return new CaptureConfig("mysql", Map.of(), List.of("orders"));
    }

    private static Envelope row(int id) {
        return Envelope.read(id, "orders", Map.of("id", id), Map.of());
    }

    @Test
    void drainsTheSnapshotBatchStraightToTheSinkInOrder() {
        List<Envelope> rows = List.of(row(1), row(2), row(3));
        FakePort port = new FakePort(new FakeBatch(rows));
        List<Envelope> sink = new ArrayList<>();

        long count = SnapshotPhase.run(
                port, config(), "chain", new SourcePosition("p0"), new RecordingMeta(new ArrayList<>()), sink::add);

        assertThat(sink).containsExactlyElementsOf(rows);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void drainPassesTheSnapshotBatchStraightToTheSinkWithoutRecordingACdcStart() {
        List<Envelope> rows = List.of(row(1), row(2), row(3));
        FakePort port = new FakePort(new FakeBatch(rows));
        List<Envelope> sink = new ArrayList<>();

        // drain is the pure pass-through with no meta collaborator: it never records a cdc-start position.
        // It is the path a snapshot_only or srs-disabled read takes, where there is no shared chain a cdc
        // tail would resume against, so there is nothing to position.
        long count = SnapshotPhase.drain(port, config(), sink::add);

        assertThat(sink).containsExactlyElementsOf(rows);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void drainClosesTheSnapshotBatch() {
        FakeBatch batch = new FakeBatch(List.of(row(1)));

        SnapshotPhase.drain(new FakePort(batch), config(), e -> {});

        assertThat(batch.closed).isTrue();
    }

    @Test
    void closesTheSnapshotBatchAfterDraining() {
        FakeBatch batch = new FakeBatch(List.of(row(1)));

        SnapshotPhase.run(
                new FakePort(batch), config(), "chain", new SourcePosition("p0"),
                new RecordingMeta(new ArrayList<>()), e -> {});

        assertThat(batch.closed).isTrue();
    }

    @Test
    void recordsTheCdcStartPositionBeforeDrainingSoTheCdcTailMissesNoChange() {
        List<String> trace = new ArrayList<>();
        RecordingMeta meta = new RecordingMeta(trace);
        Consumer<Envelope> sink = e -> trace.add("event");

        SnapshotPhase.run(
                new FakePort(new FakeBatch(List.of(row(1), row(2)))), config(), "chain",
                new SourcePosition("binlog.000042:1024"), meta, sink);

        // The cdc-start position is the source log position sampled at snapshot start: recorded before the
        // snapshot drains, so the cdc tail resumes from before the snapshot and the idempotent sink absorbs
        // the overlap -- a change made during the snapshot is never missed.
        assertThat(meta.cdcStart).isEqualTo("binlog.000042:1024");
        assertThat(trace).containsExactly("cdc-start", "event", "event");
    }

    @Test
    void closesTheSnapshotBatchEvenWhenTheSinkThrows() {
        FakeBatch batch = new FakeBatch(List.of(row(1)));
        Consumer<Envelope> failing = e -> {
            throw new IllegalStateException("sink down");
        };

        assertThatThrownBy(() -> SnapshotPhase.run(
                new FakePort(batch), config(), "chain", new SourcePosition("p0"),
                new RecordingMeta(new ArrayList<>()), failing))
                .isInstanceOf(IllegalStateException.class);

        // try-with-resources releases the source even when the drain fails partway.
        assertThat(batch.closed).isTrue();
    }

    @Test
    void rejectsANullSinkBeforeTouchingTheStore() {
        List<String> trace = new ArrayList<>();

        assertThatThrownBy(() -> SnapshotPhase.run(
                new FakePort(new FakeBatch(List.of())), config(), "chain",
                new SourcePosition("p0"), new RecordingMeta(trace), null))
                .isInstanceOf(NullPointerException.class);

        // Args are validated up front: a null sink fails fast without first recording the cdc-start position.
        assertThat(trace).isEmpty();
    }

    /** A bounded snapshot batch over a fixed list of events; records whether it was closed. */
    private static final class FakeBatch implements CaptureBatch {
        private final Iterator<Envelope> events;
        boolean closed;

        FakeBatch(List<Envelope> events) {
            this.events = events.iterator();
        }

        @Override
        public boolean hasNext() {
            return events.hasNext();
        }

        @Override
        public Envelope next() {
            return events.next();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A capture port that yields one fixed snapshot batch; the streaming and discovery reads are unused here. */
    private static final class FakePort implements CapturePort {
        private final FakeBatch batch;

        FakePort(FakeBatch batch) {
            this.batch = batch;
        }

        @Override
        public CaptureBatch snapshot(CaptureConfig config) {
            return batch;
        }

        @Override
        public Subscription cdc(CaptureConfig config, CaptureListener listener) {
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

    /** A meta store that records only the cdc-start position; the other facets are unused in the snapshot phase. */
    private static final class RecordingMeta implements SrsMetaStore {
        private final List<String> trace;
        String cdcStart;

        RecordingMeta(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public void setCdcStartPosition(String miningChainId, String cdcStartPosition) {
            this.cdcStart = cdcStartPosition;
            trace.add("cdc-start");
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
        public void advanceSourceReadOffset(String miningChainId, String sourceReadOffset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void upsertConsumerOffset(String miningChainId, ConsumerOffset offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void advanceConsumerReadSeq(String miningChainId, String pipelineId, String table, long lastReadSeq) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendSchemaVersion(String miningChainId, SchemaVersion version) {
            throw new UnsupportedOperationException();
        }
    }
}
