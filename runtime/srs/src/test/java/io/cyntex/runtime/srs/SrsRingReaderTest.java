package io.cyntex.runtime.srs;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.cyntex.core.event.Op;
import io.cyntex.spi.capture.SourcePosition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-consumer ring reader tails one per-table change ring, advancing a run-local cursor by
 * sequence and emitting each change once. A fill drains a bounded batch from the cursor up to the ring
 * tail — bounded so the reader respects the downstream's pull (Jet backpressure), and never blocking for
 * a change that has not been written yet. The cursor is run-local: it is not durable and does not resume
 * from a Jet snapshot; on an L1 restart the ring is gone and the reader replays a freshly re-mined ring
 * from its head.
 */
class SrsRingReaderTest {

    private static HazelcastInstance hz;

    @BeforeAll
    static void startMember() {
        Config config = new Config();
        // Isolated, structurally undiscoverable single member — never merge with anything on the LAN.
        config.setClusterName("srs-reader-test-" + System.nanoTime());
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

    private static SrsItem insert(int id) {
        return new SrsItem(new SourcePosition("w" + id), Op.INSERT, 1L, null, Map.of("id", id), 0L);
    }

    /** A ring pre-filled with {@code count} inserts at sequences {@code 0..count-1}. */
    private static SrsRingbuffer filled(String ringName, int count) {
        SrsRingbuffer ring = new SrsRingbuffer(hz.getRingbuffer(ringName));
        for (int i = 0; i < count; i++) {
            ring.append(insert(i));
        }
        return ring;
    }

    @Test
    void readsAllChangesFromTheStartInOrder() {
        SrsRingReader reader = new SrsRingReader(filled("srs.chain.all", 3), 0);
        List<SrsItem> out = new ArrayList<>();

        int n = reader.fill(out::add, 10);

        assertThat(n).isEqualTo(3);
        assertThat(out).extracting(i -> i.after().get("id")).containsExactly(0, 1, 2);
    }

    @Test
    void boundsEachFillToTheRequestedMaxAndResumesWhereItLeftOff() {
        SrsRingReader reader = new SrsRingReader(filled("srs.chain.bounded", 3), 0);
        List<SrsItem> out = new ArrayList<>();

        // A fill drains at most max, so the reader yields to the downstream between batches (backpressure);
        // the next fill picks up exactly where the last stopped — no change re-read, none skipped.
        assertThat(reader.fill(out::add, 2)).isEqualTo(2);
        assertThat(reader.fill(out::add, 2)).isEqualTo(1);
        assertThat(out).extracting(i -> i.after().get("id")).containsExactly(0, 1, 2);
    }

    @Test
    void stopsAtTheTailWithoutBlockingForUnwrittenChanges() {
        SrsRingReader reader = new SrsRingReader(filled("srs.chain.tail", 2), 0);
        List<SrsItem> out = new ArrayList<>();

        // max exceeds what the ring holds: the reader returns what is present rather than blocking for a
        // change past the tail that has not been written yet.
        int n = reader.fill(out::add, 10);

        assertThat(n).isEqualTo(2);
        assertThat(out).hasSize(2);
    }

    @Test
    void startsFromTheGivenStartSequence() {
        SrsRingReader reader = new SrsRingReader(filled("srs.chain.start", 4), 2);
        List<SrsItem> out = new ArrayList<>();

        int n = reader.fill(out::add, 10);

        assertThat(n).isEqualTo(2);
        assertThat(out).extracting(i -> i.after().get("id")).containsExactly(2, 3);
    }

    @Test
    void emitsNothingWhenThereIsNoNewChange() {
        // Start past the tail (sequences 0,1 are present): nothing to read.
        SrsRingReader reader = new SrsRingReader(filled("srs.chain.drained", 2), 2);
        List<SrsItem> out = new ArrayList<>();

        assertThat(reader.fill(out::add, 10)).isEqualTo(0);
        assertThat(out).isEmpty();
    }

    @Test
    void tailsChangesAppendedAfterAnEmptyFill() {
        SrsRingbuffer ring = filled("srs.chain.continue", 2);
        SrsRingReader reader = new SrsRingReader(ring, 0);
        List<SrsItem> out = new ArrayList<>();

        assertThat(reader.fill(out::add, 10)).isEqualTo(2);
        assertThat(reader.fill(out::add, 10)).isEqualTo(0);
        // A change appended after an exhausted fill is picked up by the next fill — the reader tails the ring.
        ring.append(insert(2));
        assertThat(reader.fill(out::add, 10)).isEqualTo(1);
        assertThat(out).extracting(i -> i.after().get("id")).containsExactly(0, 1, 2);
    }

    @Test
    void rejectsANullRing() {
        assertThatThrownBy(() -> new SrsRingReader(null, 0)).isInstanceOf(NullPointerException.class);
    }
}
