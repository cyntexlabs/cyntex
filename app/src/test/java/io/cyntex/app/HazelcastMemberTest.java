package io.cyntex.app;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.core.HazelcastInstance;
import io.cyntex.runtime.srs.CaptureRunUnit;
import io.cyntex.runtime.srs.SrsItem;
import io.cyntex.runtime.srs.SrsItemSerializer;
import io.cyntex.spi.store.ConsumerOffset;
import io.cyntex.spi.store.SchemaVersion;
import io.cyntex.spi.store.SrsMeta;
import io.cyntex.spi.store.SrsMetaStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The embedded Hazelcast member wired by the assembly root: exactly one full member per process,
 * structurally undiscoverable, cluster name pinned, and the Jet engine enabled with a configurable
 * cooperative thread count.
 *
 * <p>Join discovery must be off explicitly: a bare {@link Config} defaults to auto-detection,
 * which falls back to multicast discovery — and a stray same-subnet member joining silently
 * would break the single-member replay invariant the runtime is built on. The listen socket is
 * loopback-only: the same port also serves the client protocol, which has no authentication.
 */
class HazelcastMemberTest {

    @Test
    void memberConfigDisablesAllJoinDiscovery() {
        JoinConfig join = HazelcastConfiguration.memberConfig(new HazelcastProperties())
                .getNetworkConfig().getJoin();
        assertThat(join.getMulticastConfig().isEnabled()).isFalse();
        assertThat(join.getTcpIpConfig().isEnabled()).isFalse();
        assertThat(join.getAutoDetectionConfig().isEnabled()).isFalse();
    }

    @Test
    void memberConfigPinsTheClusterName() {
        // "cyntex" replaces the Hazelcast default ("dev") as a second isolation fence: even a
        // misconfigured join path cannot merge members that disagree on the cluster name.
        Config config = HazelcastConfiguration.memberConfig(new HazelcastProperties());
        assertThat(config.getClusterName()).isEqualTo("cyntex");
        // The name is an operator knob, not a constant: an override must reach the config.
        HazelcastProperties overridden = new HazelcastProperties();
        overridden.setClusterName("cyntex-two");
        assertThat(HazelcastConfiguration.memberConfig(overridden).getClusterName())
                .isEqualTo("cyntex-two");
    }

    @Test
    void memberBindsLoopbackOnly() {
        // The member port also serves the (unauthenticated) client protocol; a single local member
        // must not listen on a LAN interface. Widening the bind is a deliberate multi-node change.
        Config config = HazelcastConfiguration.memberConfig(new HazelcastProperties());
        assertThat(config.getProperty("hazelcast.socket.bind.any")).isEqualTo("false");
        assertThat(config.getNetworkConfig().getInterfaces().isEnabled()).isTrue();
        assertThat(config.getNetworkConfig().getInterfaces().getInterfaces())
                .containsExactly("127.0.0.1");
    }

    @Test
    void memberConfigPinsThePolicyProperties() {
        // Policy lines, not tuning: member logs flow through the process logging setup, the context
        // owns shutdown (no competing JVM hook), and an embedded member never reports usage data.
        Config config = HazelcastConfiguration.memberConfig(new HazelcastProperties());
        assertThat(config.getProperty("hazelcast.logging.type")).isEqualTo("slf4j");
        assertThat(config.getProperty("hazelcast.shutdownhook.enabled")).isEqualTo("false");
        assertThat(config.getProperty("hazelcast.phone.home.enabled")).isEqualTo("false");
    }

    @Test
    void memberConfigEnablesJetWithTheDefaultCooperativeThreadCount() {
        Config config = HazelcastConfiguration.memberConfig(new HazelcastProperties());
        assertThat(config.getJetConfig().isEnabled()).isTrue();
        assertThat(config.getJetConfig().getCooperativeThreadCount())
                .isEqualTo(Runtime.getRuntime().availableProcessors());
    }

    @Test
    void cooperativeThreadCountIsOverridable() {
        HazelcastProperties properties = new HazelcastProperties();
        properties.getJet().setCooperativeThreadCount(2);
        Config config = HazelcastConfiguration.memberConfig(properties);
        assertThat(config.getJetConfig().getCooperativeThreadCount()).isEqualTo(2);
    }

    @Test
    void memberConfigRegistersTheSrsChangeRingItemSerializer() {
        // The change-ring item is not zero-config serializable (its heterogeneous row map defeats Compact),
        // so its stream serializer must be registered on the member for ring storage and Jet transport alike.
        Config config = HazelcastConfiguration.memberConfig(new HazelcastProperties());
        assertThat(config.getSerializationConfig().getSerializerConfigs())
                .anySatisfy(serializer -> {
                    assertThat(serializer.getTypeClass()).isEqualTo(SrsItem.class);
                    assertThat(serializer.getImplementation()).isInstanceOf(SrsItemSerializer.class);
                });
    }

    @Test
    void memberConfigDefinesTheBoundedSrsChangeRing() {
        // The per-table change rings (srs.<chain>.<table>) are the SRS's only hot buffer: bounded, in
        // memory, no time expiry (headroom backpressure -- not TTL -- guards unread overwrites), no backups
        // (single node). The wildcard config applies to every ring the capture runtime names under srs.*.
        Config config = HazelcastConfiguration.memberConfig(new HazelcastProperties());
        RingbufferConfig ring = config.getRingbufferConfigs().get("srs.*");
        assertThat(ring).isNotNull();
        assertThat(ring.getTimeToLiveSeconds()).isZero();
        assertThat(ring.getBackupCount()).isZero();
        assertThat(ring.getInMemoryFormat()).isEqualTo(InMemoryFormat.OBJECT);
        assertThat(ring.getCapacity()).isEqualTo(1024);
    }

    @Test
    void hazelcastMemberBindsTheMetaStoreIntoTheUserContext() {
        SrsMetaStore meta = new SentinelMetaStore();
        HazelcastInstance member = new HazelcastConfiguration()
                .hazelcastMember(new HazelcastProperties(), meta);
        try {
            // The read-cursor publisher factory resolves the store member-side from the user context, so the
            // assembly root binds it under the well-known key -- otherwise cursor publishing silently no-ops.
            assertThat(member.getUserContext().get(CaptureRunUnit.SRS_META_USER_CONTEXT_KEY)).isSameAs(meta);
        } finally {
            member.shutdown();
        }
    }

    @Test
    void hazelcastMemberLeavesTheUserContextUnboundWhenNoStoreIsConfigured() {
        HazelcastInstance member = new HazelcastConfiguration()
                .hazelcastMember(new HazelcastProperties(), null);
        try {
            // A run with no store (mongo disabled) binds nothing; the publisher then resolves no store and
            // cursor publishing is a documented no-op rather than a failure.
            assertThat(member.getUserContext()).doesNotContainKey(CaptureRunUnit.SRS_META_USER_CONTEXT_KEY);
        } finally {
            member.shutdown();
        }
    }

    @Test
    void embeddedMemberAndJetComeUpAndDownWithTheContext() {
        HazelcastInstance member;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Bootstrap.class)
                .web(WebApplicationType.NONE)
                .properties("cyntex.store.mongo.enabled=false",
                        "cyntex.hz.jet.cooperative-thread-count=2")
                .run()) {
            member = context.getBean(HazelcastInstance.class);
            assertThat(member.getLifecycleService().isRunning()).isTrue();
            assertThat(member.getConfig().getClusterName()).isEqualTo("cyntex");
            assertThat(member.getCluster().getMembers()).hasSize(1);
            // The live member picked the loopback address, not a LAN interface.
            assertThat(member.getCluster().getLocalMember().getAddress().getHost())
                    .isEqualTo("127.0.0.1");
            // The cooperative thread count property reaches the member through the binder.
            assertThat(member.getConfig().getJetConfig().getCooperativeThreadCount()).isEqualTo(2);
            // The Jet engine is up and reachable; no jobs exist at the substrate level.
            assertThat(member.getJet().getJobs()).isEmpty();
        }
        // The member's lifecycle is bound to the context: closing the context shuts it down.
        assertThat(member.getLifecycleService().isRunning()).isFalse();
    }

    /** A sentinel meta store: an identity to assert the user-context binding; its facets are never invoked here. */
    private static final class SentinelMetaStore implements SrsMetaStore {
        @Override public Optional<SrsMeta> read(String miningChainId) {
            throw new UnsupportedOperationException();
        }

        @Override public void create(String miningChainId, String retention) {
            throw new UnsupportedOperationException();
        }

        @Override public void upsertConsumerOffset(String miningChainId, ConsumerOffset offset) {
            throw new UnsupportedOperationException();
        }

        @Override public void advanceConsumerReadSeq(String miningChainId, String pipelineId, String table, long lastReadSeq) {
            throw new UnsupportedOperationException();
        }

        @Override public void advanceSinkAckedSrcpos(String miningChainId, String pipelineId, String srcpos) {
            throw new UnsupportedOperationException();
        }

        @Override public void advanceSourceReadOffset(String miningChainId, String sourceReadOffset) {
            throw new UnsupportedOperationException();
        }

        @Override public void setCdcStartPosition(String miningChainId, String cdcStartPosition) {
            throw new UnsupportedOperationException();
        }

        @Override public void appendSchemaVersion(String miningChainId, SchemaVersion version) {
            throw new UnsupportedOperationException();
        }
    }
}
