package io.cyntex.runtime.srs;

import static com.hazelcast.jet.core.Edge.between;
import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.cluster.Address;
import com.hazelcast.collection.IList;
import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.EdgeConfig;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.JobStatus;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.processor.Processors;
import com.hazelcast.jet.core.processor.SinkProcessors;
import com.hazelcast.jet.core.test.TestProcessorMetaSupplierContext;
import io.cyntex.core.event.Envelope;
import io.cyntex.core.event.Op;
import io.cyntex.spi.capture.SourcePosition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The core-API projected source over a per-table change ring: a {@code ProcessorMetaSupplier} that tails the
 * ring and emits each change already projected to the transform-facing {@link Envelope} currency - the shape
 * the engine's DAG builder wires as a source vertex. Where {@link SrsRingSourceTest} proves the pipeline-API
 * source over raw {@link SrsItem}s, this proves the core-API vertex that injects the stream name and carries
 * the source position into the envelope, runs inside a real Jet job, stays live as a tail, honours
 * backpressure without loss, and publishes its read cursor member-side. Runs over one embedded Jet-enabled
 * member (ring capacity 8).
 */
class SrsSourceProcessorTest {

    private static final String CURSOR_KEY = "test.source.cursor";

    private static HazelcastInstance hz;

    @BeforeAll
    static void startMember() {
        Config config = new Config();
        config.setClusterName("srs-source-p-test-" + System.nanoTime());
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getJetConfig().setEnabled(true);
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

    @Test
    void streams_changes_projected_to_envelopes_carrying_the_stream_name_and_source_position()
            throws InterruptedException {
        fill("srs.chain.orders", 5);

        // source -> project(Envelope to an assertable string) -> list. The projection is what a raw-SrsItem
        // source could not do: it injects the stream name and lifts the position token into the envelope.
        List<String> out = runProjectedToStrings("srs.chain.orders", "orders", "out-orders", 5, 1024);

        assertThat(out).containsExactly(
                "orders|w0|0", "orders|w1|1", "orders|w2|2", "orders|w3|3", "orders|w4|4");
    }

    @Test
    void loses_no_change_when_the_downstream_backpressures() throws InterruptedException {
        fill("srs.chain.bp", 6);

        // A one-deep edge queue forces the source's outbox to reject mid-fill, so the source must buffer the
        // unemitted remainder and finish it on a later run rather than advancing past it. Every change still
        // arrives, in order - the proof the projected source honours Jet backpressure without dropping a read.
        List<String> out = runProjectedToStrings("srs.chain.bp", "orders", "out-bp", 6, 1);

        assertThat(out).containsExactly(
                "orders|w0|0", "orders|w1|1", "orders|w2|2", "orders|w3|3", "orders|w4|4", "orders|w5|5");
    }

    @Test
    void keeps_tailing_the_ring_after_it_has_drained_the_initial_backlog() throws InterruptedException {
        fill("srs.chain.live", 3);

        // A change ring is a live tail, not a bounded read: once the initial backlog drains the source must
        // stay running and pick up changes appended afterwards. If it completed on catching up, an append made
        // while the job runs would never be tailed - fatal for a source whose offset truth is not in Jet state.
        Job job = hz.getJet().newJob(projectedDag(
                "srs.chain.live", "orders", "out-live", 1024, SrsReadCursorPublisherFactory.NONE));
        IList<String> out = hz.getList("out-live");
        try {
            awaitSize(out, 3);
            assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

            // Append after the job is already running: a live tail must deliver it.
            new SrsRingbuffer(hz.getRingbuffer("srs.chain.live"))
                    .append(new SrsItem(new SourcePosition("w3"), Op.INSERT, 1L, null, Map.of("id", 3), 0L));
            awaitSize(out, 4);

            assertThat(out).containsExactly(
                    "orders|w0|0", "orders|w1|1", "orders|w2|2", "orders|w3|3");
        } finally {
            job.cancel();
        }
    }

    @Test
    void publishes_the_read_cursor_member_side_as_it_drains() throws InterruptedException {
        PUBLISHED.clear();
        fill("srs.chain.cursor", 5);

        // The factory must resolve its sink off the member the source runs on: it is bound into the member's
        // user context and read back through the member argument, so a source that resolved with a wrong or
        // null instance would fail here rather than silently reporting nothing.
        hz.getUserContext().put(CURSOR_KEY, (LongConsumer) SrsSourceProcessorTest::collect);
        SrsReadCursorPublisherFactory factory =
                member -> (LongConsumer) member.getUserContext().get(CURSOR_KEY);
        Job job = hz.getJet().newJob(projectedDag("srs.chain.cursor", "orders", "out-cursor", 1024, factory));
        IList<String> out = hz.getList("out-cursor");
        try {
            awaitSize(out, 5);
        } finally {
            job.cancel();
            hz.getUserContext().remove(CURSOR_KEY);
        }

        // The source reports its read progress member-side: the last sequence it read (4, the 5th change).
        assertThat(PUBLISHED).isNotEmpty();
        assertThat(PUBLISHED.get(PUBLISHED.size() - 1)).isEqualTo(4L);
    }

    @Test
    void emits_all_buffered_snapshot_rows_before_it_tails_the_ring() throws InterruptedException {
        // A snapshot buffer bound member-side carries the source's snapshot rows; the ring holds the cdc tail.
        // The source must drain the whole buffer first, then tail the ring -- snapshot (op r, no position)
        // strictly before cdc (op i, positioned), the ordering that keeps a stale snapshot from landing after
        // a newer change. Distinct id ranges make the two streams unmistakable in the observed order.
        SnapshotBuffer buffer = new SnapshotBuffer();
        buffer.append("srs.chain.snapfirst", snapshotRow(100));
        buffer.append("srs.chain.snapfirst", snapshotRow(101));
        buffer.append("srs.chain.snapfirst", snapshotRow(102));
        fill("srs.chain.snapfirst", 2);
        hz.getUserContext().put(SnapshotBuffer.USER_CONTEXT_KEY, buffer);

        List<String> out;
        try {
            out = runProjectedToStrings("srs.chain.snapfirst", "orders", "out-snapfirst", 5, 1024);
        } finally {
            hz.getUserContext().remove(SnapshotBuffer.USER_CONTEXT_KEY);
        }

        // The three snapshot rows (no source position) come first, in buffered order, then the two cdc changes.
        assertThat(out).containsExactly(
                "orders|null|100", "orders|null|101", "orders|null|102", "orders|w0|0", "orders|w1|1");
    }

    @Test
    void tails_the_ring_unchanged_when_no_snapshot_buffer_is_bound() throws InterruptedException {
        // The cdc-only path: no buffer bound, so the source behaves exactly as before -- pure ring tail.
        fill("srs.chain.nobuffer", 3);

        List<String> out = runProjectedToStrings("srs.chain.nobuffer", "orders", "out-nobuffer", 3, 1024);

        assertThat(out).containsExactly("orders|w0|0", "orders|w1|1", "orders|w2|2");
    }

    @Test
    void pins_the_source_vertex_to_a_single_instance_across_the_cluster() throws Exception {
        // One reader per ring is what keeps the change stream in order; a per-member instance would re-lane it.
        // A static resolution check: a total-parallelism-one supplier hands the real supplier to one member and
        // a no-op to the rest, so resolving over several members yields more than one distinct supplier.
        ProcessorMetaSupplier meta = SrsSourceProcessor.metaSupplier(
                "srs.chain.pins", "orders", StartFrom.earliest(), SrsReadCursorPublisherFactory.NONE);
        List<Address> addresses = List.of(
                Address.createUnresolvedAddress("10.0.0.1", 5701),
                Address.createUnresolvedAddress("10.0.0.2", 5702),
                Address.createUnresolvedAddress("10.0.0.3", 5703));
        meta.init(new TestProcessorMetaSupplierContext().setTotalParallelism(3).setLocalParallelism(1));
        Function<? super Address, ? extends ProcessorSupplier> assignment = meta.get(addresses);

        assertThat(addresses.stream().map(assignment).distinct().count()).isGreaterThan(1);
    }

    /** Runs source -> project-to-string -> list with a bounded edge queue, waiting until {@code size} arrive. */
    private static List<String> runProjectedToStrings(
            String ringName, String src, String sinkName, int size, int queueSize) throws InterruptedException {
        Job job = hz.getJet().newJob(
                projectedDag(ringName, src, sinkName, queueSize, SrsReadCursorPublisherFactory.NONE));
        IList<String> out = hz.getList(sinkName);
        try {
            awaitSize(out, size);
        } finally {
            job.cancel();
        }
        return List.copyOf(out);
    }

    /**
     * source -> project-to-string -> list, every stage at parallelism one as the production pipeline is (source,
     * transform and sink are each pinned to one instance): a default-parallelism map or list sink would fan the
     * single ordered stream across racing instances and the observed list order would no longer be the read order.
     */
    private static DAG projectedDag(String ringName, String src, String sinkName, int queueSize,
            SrsReadCursorPublisherFactory publisherFactory) {
        DAG dag = new DAG();
        Vertex source = dag.newVertex("source",
                SrsSourceProcessor.metaSupplier(ringName, src, StartFrom.earliest(), publisherFactory));
        Vertex project = dag.newVertex("project", Processors.mapP(SrsSourceProcessorTest::describe))
                .localParallelism(1);
        Vertex sink = dag.newVertex("sink", SinkProcessors.writeListP(sinkName)).localParallelism(1);
        dag.edge(between(source, project).setConfig(new EdgeConfig().setQueueSize(queueSize)))
                .edge(between(project, sink));
        return dag;
    }

    /** A stable, Hazelcast-serializable projection of an envelope: {@code src|srcPos|id}. */
    private static String describe(Envelope event) {
        return event.src() + "|" + event.srcPos() + "|" + event.after().get("id");
    }

    /** A snapshot read envelope (op r, no source position) on the {@code orders} stream. */
    private static Envelope snapshotRow(int id) {
        return Envelope.read(1L, "orders", Map.of("id", id), Map.of());
    }

    /** Pre-fills a ring with {@code count} inserts at sequences {@code 0..count-1}, positions {@code w0..}. */
    private static void fill(String ringName, int count) {
        SrsRingbuffer ring = new SrsRingbuffer(hz.getRingbuffer(ringName));
        for (int i = 0; i < count; i++) {
            ring.append(new SrsItem(new SourcePosition("w" + i), Op.INSERT, 1L, null, Map.of("id", i), 0L));
        }
    }

    private static void awaitSize(IList<?> list, int size) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (list.size() < size) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + size + " changes, got " + list.size());
            }
            Thread.sleep(50);
        }
    }

    private static final List<Long> PUBLISHED = new CopyOnWriteArrayList<>();

    private static void collect(long lastReadSeq) {
        PUBLISHED.add(lastReadSeq);
    }
}
