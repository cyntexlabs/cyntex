package io.cyntex.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.function.SupplierEx;
import io.cyntex.adapters.pdk.ConnectorProvisioner;
import io.cyntex.core.event.Envelope;
import io.cyntex.core.model.FromClause;
import io.cyntex.core.model.FromRef;
import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.ReadMode;
import io.cyntex.core.model.ServeBlock;
import io.cyntex.core.model.Settings;
import io.cyntex.core.model.SourceMode;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.Step;
import io.cyntex.core.model.SyncElement;
import io.cyntex.core.model.TableRef;
import io.cyntex.core.model.TransformBody;
import io.cyntex.runtime.engine.Engine;
import io.cyntex.runtime.scheduler.LifecycleActuator;
import io.cyntex.runtime.srs.CaptureRunUnit;
import io.cyntex.runtime.srs.SnapshotBuffer;
import io.cyntex.runtime.srs.SrsCoordinator;
import io.cyntex.runtime.srs.SrsItem;
import io.cyntex.runtime.srs.SrsItemSerializer;
import io.cyntex.spi.capture.CaptureBatch;
import io.cyntex.spi.capture.CaptureConfig;
import io.cyntex.spi.capture.CaptureListener;
import io.cyntex.spi.capture.CapturePort;
import io.cyntex.spi.capture.ConnectionReport;
import io.cyntex.spi.capture.DiscoveredSchema;
import io.cyntex.spi.capture.Subscription;
import io.cyntex.spi.sink.SinkWriter;
import io.cyntex.spi.sink.WriteResult;
import io.cyntex.spi.store.ConsumerOffset;
import io.cyntex.spi.store.SrsMetaStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The durable-frontier proof for the sink-ack loop: as the sink confirms writes, the pipeline's durable
 * {@code sinkAckedSrcpos} advances in the SRS meta, so the source-read frontier has a real input. It runs
 * the whole assembly end to end — cdc capture fills the ring, the store-backed topology reads it, a real
 * transform runs, and the sink advances the durable watermark — over one embedded Jet + SRS member.
 *
 * <p>Changes are fed one at a time and each is awaited at the sink before the next, so a change lands in
 * its own sink batch. Two effects set how far the durable ack advances: the contiguous-acked-prefix always
 * holds the highest position open (a position acks only once a strictly higher one settles), and a live
 * streaming sink reaps a settled batch on the next input rather than on a completion call that never comes,
 * so the second-highest's ack is pending until one more input arrives. With positions {@code w1..w4} fed
 * one per batch the durable prefix therefore reaches {@code w2}; {@code w3} and {@code w4} stay pending —
 * the most the algorithm guarantees here.
 *
 * <p>Scope: {@code cdc_only}, so the snapshot phase does not run.
 */
class CaptureToSinkAckFrontierTest {

    private static final String PIPELINE = "p";
    private static final String SOURCE_ID = "orders_src";
    private static final String DEST_ID = "orders_dest";
    private static final String TABLE = "orders";

    private HazelcastInstance member;

    @BeforeEach
    void startMember() {
        Config config = new Config();
        config.setClusterName("capture-to-sink-ack-test-" + System.nanoTime());
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getJetConfig().setEnabled(true).setCooperativeThreadCount(2);
        config.addRingBufferConfig(new RingbufferConfig("srs.*")
                .setCapacity(16)
                .setInMemoryFormat(InMemoryFormat.OBJECT)
                .setTimeToLiveSeconds(0)
                .setBackupCount(0));
        config.getSerializationConfig().addSerializerConfig(
                new SerializerConfig().setImplementation(new SrsItemSerializer()).setTypeClass(SrsItem.class));
        member = Hazelcast.newHazelcastInstance(config);
        CapturingSinkWriter.reset();
    }

    @AfterEach
    void stopMember() {
        if (member != null) {
            member.shutdown();
        }
    }

    @Test
    @DisplayName("the sink advances the pipeline's durable sinkAckedSrcpos as it confirms writes")
    void sinkConfirmsAdvanceTheDurableSinkAckedSourcePosition() {
        SourceResource source = new SourceResource(SOURCE_ID, null, "fake", Map.of("host", "h"), SourceMode.CDC,
                List.of(TableRef.literal(TABLE)), null, null, null);
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.save(source);
        artifacts.save(new SourceResource(DEST_ID, null, "fake", Map.of("host", "d"), null, null, null, null, null));
        // A passthrough filter (keeps every change), so every fed position reaches the sink and the sink size
        // tracks the number of changes fed. cdc_only, so only the change tail runs.
        artifacts.save(new PipelineResource(PIPELINE, null, List.of(SOURCE_ID),
                List.of(Step.inline("keep_all", FromClause.list(FromRef.literal(SOURCE_ID)),
                        new TransformBody.Filter("true"), null, null)),
                null,
                new ServeBlock.Inline(null, FromRef.literal("keep_all"),
                        List.of(new SyncElement("sync_1", DEST_ID, null, null, null, null)), null, null),
                new Settings(null, null, null, null, ReadMode.CDC_ONLY, "earliest"), null));
        InMemoryStorePort store = new InMemoryStorePort(artifacts);

        // Make the member SRS-capable and sink-capable, as the assembly root does.
        SrsMetaStore meta = store.meta();
        member.getUserContext().put(CaptureRunUnit.SRS_META_USER_CONTEXT_KEY, meta);
        ConnectorProvisioner provisioner = connectorId -> {
            throw new UnsupportedOperationException("not resolved by this ack test");
        };
        member.getUserContext().put(PdkSinkWriterFactory.CONNECTOR_PROVISIONER_USER_CONTEXT_KEY, provisioner);

        SnapshotBuffer snapshotBuffer = new SnapshotBuffer();
        member.getUserContext().put(SnapshotBuffer.USER_CONTEXT_KEY, snapshotBuffer);

        GatedSource gatedSource = new GatedSource();
        SrsCoordinator srsCoordinator = new SrsCoordinator(meta);
        CaptureRunUnit captureRunUnit = new CaptureRunUnit(gatedSource, srsCoordinator, meta, member);
        PipelineCaptureCoordinator coordinator =
                new StoreBackedPipelineCaptureCoordinator(store, captureRunUnit::start, srsCoordinator, snapshotBuffer);

        StoreBackedDagSource.SinkWriterBinder capturingSink =
                (connectorId, settings, writeMode, ddl, target) -> (SupplierEx<SinkWriter>) CapturingSinkWriter::new;
        DagSource dagSource = new StoreBackedDagSource(store, capturingSink);
        LifecycleActuator actuator = new EngineLifecycleActuator(new Engine(member), dagSource, coordinator);

        String chainId = SourceCaptureResolution.of(source).chainId().value();

        actuator.start(PIPELINE);
        try {
            // Feed four cdc changes one at a time, each awaited at the sink so it lands in its own batch:
            // positions w1, w2, w3, w4 in feed order. Every settled batch is reaped - on the next input while
            // they arrive, and on the idle hook once the tail goes quiet after the last one - so the durable
            // acked prefix settles at its lag-by-one frontier: w1..w3 are acked, and only w4 is held open,
            // since nothing strictly higher than it has settled to close it.
            gatedSource.feed(change(0));
            awaitSinkSize(1);
            gatedSource.feed(change(1));
            awaitSinkSize(2);
            gatedSource.feed(change(2));
            awaitSinkSize(3);
            gatedSource.feed(change(3));
            awaitSinkSize(4);

            awaitSinkAck(meta, chainId, "w3");
            // w4 is held open by the lag-by-one rule: nothing strictly higher has settled to close it.
            assertThat(ackedPosition(meta, chainId)).isEqualTo("w3");

            // The observation position resolver reads back exactly that durable sink-acked position, keyed by
            // the source's table, so the read face projects what the real sink advanced -- not a stand-in.
            assertThat(new StoreBackedSinkPositions(store).apply(PIPELINE))
                    .containsExactly(entry(TABLE, "w3"));
        } finally {
            actuator.stop(PIPELINE);
        }

        assertThat(gatedSource.cdcClosed).as("stop closes the capture subscription").isTrue();
    }

    private static Envelope change(int id) {
        return Envelope.insert(id, TABLE, Map.of("id", (long) id), Map.of());
    }

    private void awaitSinkSize(int size) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (CapturingSinkWriter.collected().size() < size) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + size + " changes at the sink, got "
                        + CapturingSinkWriter.collected().size() + ": " + CapturingSinkWriter.collected());
            }
            park();
        }
    }

    private void awaitSinkAck(SrsMetaStore meta, String chainId, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!expected.equals(ackedPosition(meta, chainId))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for durable sinkAckedSrcpos=" + expected
                        + ", last observed=" + ackedPosition(meta, chainId)
                        + ", collected srcPos=" + CapturingSinkWriter.collected());
            }
            park();
        }
    }

    private static String ackedPosition(SrsMetaStore meta, String chainId) {
        return meta.read(chainId).map(record -> record.consumerOffsets().stream()
                .filter(offset -> offset.pipelineId().equals(PIPELINE))
                .map(ConsumerOffset::sinkAckedSrcpos)
                .findFirst()
                .orElse(null)).orElse(null);
    }

    private static void park() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while polling", e);
        }
    }

    /**
     * A fake connector whose cdc stream is driven on demand: {@code cdc} starts a daemon that emits each fed
     * change to the listener, so the test can release changes one at a time while the pipeline runs live.
     */
    private static final class GatedSource implements CapturePort {

        private final LinkedBlockingQueue<Envelope> pending = new LinkedBlockingQueue<>();
        private volatile boolean running;
        private volatile boolean cdcClosed;
        private Thread daemon;

        void feed(Envelope change) {
            pending.add(change);
        }

        @Override
        public CaptureBatch snapshot(CaptureConfig config) {
            return new FakeBatch();
        }

        @Override
        public Subscription cdc(CaptureConfig config, CaptureListener listener) {
            running = true;
            daemon = new Thread(() -> {
                while (running) {
                    try {
                        Envelope change = pending.poll(25, TimeUnit.MILLISECONDS);
                        if (change != null) {
                            listener.onEvent(change);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "gated-source-cdc");
            daemon.setDaemon(true);
            daemon.start();
            return () -> {
                running = false;
                cdcClosed = true;
                daemon.interrupt();
            };
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

    /** An empty snapshot batch: cdc_only never drains one, but the port contract requires the method. */
    private static final class FakeBatch implements CaptureBatch {
        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public Envelope next() {
            throw new java.util.NoSuchElementException();
        }

        @Override
        public void close() {
        }
    }

    /** Records each event's src position into a JVM-static queue, shared with the test thread on one member. */
    private static final class CapturingSinkWriter implements SinkWriter {

        private static final Queue<String> COLLECTED = new ConcurrentLinkedQueue<>();

        static Queue<String> collected() {
            return COLLECTED;
        }

        static void reset() {
            COLLECTED.clear();
        }

        @Override
        public CompletionStage<WriteResult> write(List<Envelope> records) {
            for (Envelope record : records) {
                COLLECTED.add(record.srcPos());
            }
            return CompletableFuture.completedFuture(new WriteResult(records.size()));
        }

        @Override
        public void close() {
        }
    }
}
