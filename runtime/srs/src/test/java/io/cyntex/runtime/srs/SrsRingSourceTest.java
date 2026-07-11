package io.cyntex.runtime.srs;

import com.hazelcast.collection.IList;
import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.StreamSource;
import io.cyntex.core.event.Op;
import io.cyntex.spi.capture.SourcePosition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The self-built Jet source over a per-table change ring: a SourceBuilder stream source that tails the
 * ring and drives its changes into a Jet pipeline in order, respecting Jet backpressure. The source is
 * deliberately not fault-tolerant — it sets no snapshot functions, so its position never enters a Jet
 * snapshot; on an L1 restart the ring is re-mined and the source replays it from the head. Runs over a
 * single embedded Jet-enabled member sized to the L1 hot-buffer shape (capacity 8).
 */
class SrsRingSourceTest {

    private static HazelcastInstance hz;

    @BeforeAll
    static void startMember() {
        Config config = new Config();
        // Isolated, structurally undiscoverable single member — never merge with anything on the LAN.
        config.setClusterName("srs-source-test-" + System.nanoTime());
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        // The Jet execution engine is on: this source runs inside a Jet job.
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

    /** Pre-fills a ring with {@code count} inserts at sequences {@code 0..count-1}. */
    private static void fill(String ringName, int count) {
        SrsRingbuffer ring = new SrsRingbuffer(hz.getRingbuffer(ringName));
        for (int i = 0; i < count; i++) {
            ring.append(new SrsItem(new SourcePosition("w" + i), Op.INSERT, 1L, null, Map.of("id", i), 0L));
        }
    }

    /** Runs a job that streams one ring into a fresh list sink, waiting until {@code size} changes arrive. */
    private static IList<SrsItem> streamRingToList(String ringName, String sinkName, int size)
            throws InterruptedException {
        Pipeline p = Pipeline.create();
        p.readFrom(SrsRingSource.create(ringName)).withoutTimestamps().writeTo(Sinks.list(sinkName));
        IList<SrsItem> sink = hz.getList(sinkName);
        Job job = hz.getJet().newJob(p);
        try {
            awaitSize(sink, size);
        } finally {
            job.cancel();
        }
        return sink;
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

    @Test
    void streamsRingChangesToADownstreamJetStageInOrder() throws InterruptedException {
        fill("srs.chain.orders", 5);

        IList<SrsItem> sink = streamRingToList("srs.chain.orders", "srs-sink-orders", 5);

        assertThat(sink).extracting(i -> i.after().get("id")).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void aFreshJobReplaysTheRingFromTheHead() throws InterruptedException {
        fill("srs.chain.replay", 4);

        // A first job drains the ring; a second, fresh job reads the same ring from the head again. The
        // source keeps no position in Jet state, so a restart replays rather than resuming a persisted
        // offset — the L1 restart=replay semantic.
        streamRingToList("srs.chain.replay", "srs-sink-replay-1", 4);
        IList<SrsItem> second = streamRingToList("srs.chain.replay", "srs-sink-replay-2", 4);

        assertThat(second).extracting(i -> i.after().get("id")).containsExactly(0, 1, 2, 3);
    }
}
