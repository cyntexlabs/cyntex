package io.cyntex.runtime.srs;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.ringbuffer.Ringbuffer;
import io.cyntex.core.event.Op;
import io.cyntex.spi.capture.SourcePosition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-table change ring over a single embedded Hazelcast member. Each test uses its own ring name
 * (the {@code srs.*} wildcard config gives them all the same small capacity) so appends never bleed
 * across tests.
 */
class SrsRingbufferTest {

    private static HazelcastInstance hz;

    @BeforeAll
    static void startMember() {
        Config config = new Config();
        // Isolated, structurally undiscoverable single member — never merge with anything on the LAN.
        config.setClusterName("srs-test-" + System.nanoTime());
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getJetConfig().setEnabled(false);
        // Every srs.* ring: small, in-memory (no time eviction, no backup) — the L1 hot buffer shape.
        config.addRingBufferConfig(new RingbufferConfig("srs.*")
                .setCapacity(8)
                .setInMemoryFormat(InMemoryFormat.OBJECT)
                .setTimeToLiveSeconds(0)
                .setBackupCount(0));
        // The ring stores SrsItem via its dedicated serializer (Hazelcast cannot serialize it natively).
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

    private static SrsItem insert(String token) {
        return new SrsItem(new SourcePosition(token), Op.INSERT, 1L, null, Map.of("id", 1), 0L);
    }

    @Test
    void ringNameNamespacesByChainAndTable() {
        assertThat(SrsRingbuffer.ringName("chain-1", "orders")).isEqualTo("srs.chain-1.orders");
    }

    @Test
    void appendAssignsMonotonicSequencesFromZero() {
        SrsRingbuffer buffer = new SrsRingbuffer(hz.getRingbuffer("srs.chain-1.mono"));
        assertThat(buffer.append(insert("a"))).isEqualTo(0L);
        assertThat(buffer.append(insert("b"))).isEqualTo(1L);
        assertThat(buffer.append(insert("c"))).isEqualTo(2L);
    }

    @Test
    void tailSequenceAdvancesWithEachAppend() {
        SrsRingbuffer buffer = new SrsRingbuffer(hz.getRingbuffer("srs.chain-1.tail"));
        assertThat(buffer.tailSequence()).isEqualTo(-1L);
        buffer.append(insert("a"));
        assertThat(buffer.tailSequence()).isEqualTo(0L);
        buffer.append(insert("b"));
        assertThat(buffer.tailSequence()).isEqualTo(1L);
    }

    @Test
    void capacityReflectsTheConfiguredBound() {
        SrsRingbuffer buffer = new SrsRingbuffer(hz.getRingbuffer("srs.chain-1.cap"));
        assertThat(buffer.capacity()).isEqualTo(8L);
    }

    @Test
    void appendedItemRoundTripsThroughTheRing() throws InterruptedException {
        Ringbuffer<SrsItem> ring = hz.getRingbuffer("srs.chain-1.roundtrip");
        SrsRingbuffer buffer = new SrsRingbuffer(ring);
        SrsItem original = new SrsItem(new SourcePosition("gtid:aaa-1:9"), Op.UPDATE, 42L,
                Map.of("id", 1, "name", "old"), Map.of("id", 1, "name", "new"), 3L);
        long seq = buffer.append(original);
        SrsItem stored = ring.readOne(seq);
        assertThat(stored).isEqualTo(original);
    }

    @Test
    void insertRoundTripsWithAnAbsentBefore() throws InterruptedException {
        Ringbuffer<SrsItem> ring = hz.getRingbuffer("srs.chain-1.insert");
        SrsRingbuffer buffer = new SrsRingbuffer(ring);
        SrsItem original = new SrsItem(new SourcePosition("gtid:aaa-1:10"), Op.INSERT, 7L,
                null, Map.of("id", 2, "name", "new"), 1L);
        long seq = buffer.append(original);
        SrsItem stored = ring.readOne(seq);
        assertThat(stored).isEqualTo(original);
        assertThat(stored.before()).isNull();
    }

    @Test
    void deleteRoundTripsWithAnAbsentAfter() throws InterruptedException {
        Ringbuffer<SrsItem> ring = hz.getRingbuffer("srs.chain-1.delete");
        SrsRingbuffer buffer = new SrsRingbuffer(ring);
        SrsItem original = new SrsItem(new SourcePosition("gtid:aaa-1:11"), Op.DELETE, 8L,
                Map.of("id", 3), null, 1L);
        long seq = buffer.append(original);
        SrsItem stored = ring.readOne(seq);
        assertThat(stored).isEqualTo(original);
        assertThat(stored.after()).isNull();
    }

    @Test
    void readOneReturnsTheItemAtASequence() {
        SrsRingbuffer buffer = new SrsRingbuffer(hz.getRingbuffer("srs.chain-1.readone"));
        long a = buffer.append(insert("a"));
        long b = buffer.append(insert("b"));
        assertThat(buffer.readOne(a)).isEqualTo(insert("a"));
        assertThat(buffer.readOne(b)).isEqualTo(insert("b"));
    }

    @Test
    void headSequenceMarksTheOldestReadableSequence() {
        SrsRingbuffer buffer = new SrsRingbuffer(hz.getRingbuffer("srs.chain-1.head"));
        // A fresh ring's head is 0 — where a replaying reader starts. Within capacity nothing is evicted,
        // so the head stays put as changes are appended.
        assertThat(buffer.headSequence()).isEqualTo(0L);
        buffer.append(insert("a"));
        buffer.append(insert("b"));
        assertThat(buffer.headSequence()).isEqualTo(0L);
    }

    @Test
    void rejectsANullItem() {
        SrsRingbuffer buffer = new SrsRingbuffer(hz.getRingbuffer("srs.chain-1.nullitem"));
        assertThatThrownBy(() -> buffer.append(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullRingbuffer() {
        assertThatThrownBy(() -> new SrsRingbuffer(null)).isInstanceOf(NullPointerException.class);
    }
}
