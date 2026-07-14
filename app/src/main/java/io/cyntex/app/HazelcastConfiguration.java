package io.cyntex.app;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import io.cyntex.adapters.pdk.ConnectorProvisioner;
import io.cyntex.core.common.CyntexException;
import io.cyntex.runtime.srs.CaptureRunUnit;
import io.cyntex.runtime.srs.SnapshotBuffer;
import io.cyntex.runtime.srs.SrsItem;
import io.cyntex.runtime.srs.SrsItemSerializer;
import io.cyntex.spi.store.SrsMetaStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Wires the embedded Hazelcast member into the assembly root: exactly one full member per process,
 * with the Jet execution engine enabled. The context owns the member's lifecycle — it is created
 * with the context and shut down when the context closes.
 *
 * <p>The member is structurally undiscoverable: every join-discovery path is disabled explicitly.
 * A bare {@link Config} defaults to auto-detection, which falls back to multicast discovery — a
 * stray same-subnet member joining silently would break the single-member replay invariant the
 * runtime is built on. The pinned cluster name is a second fence: members that disagree on the
 * name never merge. The listen socket is loopback-only: the member port also serves the
 * (unauthenticated) client protocol, so a single local member must not be reachable from the LAN;
 * widening the bind is a deliberate multi-node change.
 */
@Configuration
@EnableConfigurationProperties(HazelcastProperties.class)
class HazelcastConfiguration {

    /**
     * The bounded capacity of each per-table SRS change ring. Headroom backpressure, not size, is the
     * primary guard against overwriting an unread change, so this is a coarse single-node default rather
     * than a tuned figure.
     */
    private static final int SRS_RING_CAPACITY = 1024;

    @Bean(destroyMethod = "shutdown")
    HazelcastInstance hazelcastMember(HazelcastProperties properties, @Nullable SrsMetaStore srsMetaStore,
            @Nullable ConnectorProvisioner connectorProvisioner, @Nullable SnapshotBuffer snapshotBuffer) {
        Config config = memberConfig(properties);
        HazelcastInstance member = startMember(() -> Hazelcast.newHazelcastInstance(config));
        // Bind the SRS meta store onto the member so the read-cursor publisher factory -- carried onto the
        // Jet source and resolved member-side -- can reach it through the user context and publish durable
        // read cursors. A run with no store (mongo disabled) binds nothing, and the publisher then no-ops.
        if (srsMetaStore != null) {
            member.getUserContext().put(CaptureRunUnit.SRS_META_USER_CONTEXT_KEY, srsMetaStore);
        }
        // Bind the connector provisioner onto the member so a sink-writer factory -- carried onto the Jet
        // sink vertex and resolved member-side -- can reach it and open its target connector. A run with no
        // provisioner (mongo disabled) binds nothing, and the member is then not sink-capable: a sink open
        // fails loudly rather than silently dropping writes.
        if (connectorProvisioner != null) {
            member.getUserContext().put(
                    PdkSinkWriterFactory.CONNECTOR_PROVISIONER_USER_CONTEXT_KEY, connectorProvisioner);
        }
        // Bind the snapshot buffer onto the member so a source vertex -- resolved member-side by the ring name
        // it carries -- can drain this ring's snapshot rows and emit them ahead of the cdc tail. The coordinator
        // holds the same instance and fills it through the snapshot pass-through. A run with no buffer (mongo
        // disabled) binds nothing, and a source then emits no snapshot ahead of the tail.
        if (snapshotBuffer != null) {
            member.getUserContext().put(SnapshotBuffer.USER_CONTEXT_KEY, snapshotBuffer);
        }
        return member;
    }

    /**
     * Starts the member, translating a Hazelcast startup failure — typically the loopback member
     * port being already in use — into a coded diagnostic so the operator sees a clean message
     * instead of a bare stack trace. Anything that is not a {@link HazelcastException} (a programmer
     * error while assembling the config) propagates unchanged: it must crash bare, not be laundered
     * into a code that hides the defect. The factory is a seam so the translation is unit-testable.
     */
    static HazelcastInstance startMember(Supplier<HazelcastInstance> factory) {
        try {
            return factory.get();
        } catch (HazelcastException cause) {
            throw new CyntexException(BootError.HAZELCAST_UNAVAILABLE, Map.of(), cause);
        }
    }

    /** Builds the single-member config; pure function, exposed for direct assertion. */
    static Config memberConfig(HazelcastProperties properties) {
        Config config = new Config();
        config.setClusterName(properties.getClusterName());
        // Member logs flow through the same operational logging setup as the rest of the process.
        config.setProperty("hazelcast.logging.type", "slf4j");
        // The context owns the member lifecycle (bean destroy); Hazelcast's own JVM shutdown hook
        // would race the context's orderly shutdown.
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        // An embedded member of a server product must not report usage data anywhere.
        config.setProperty("hazelcast.phone.home.enabled", "false");
        // Loopback-only listen socket: the member port also serves the unauthenticated client
        // protocol, so a single local member must not expose it on a LAN interface.
        config.setProperty("hazelcast.socket.bind.any", "false");
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        config.getJetConfig().setEnabled(true);
        Integer cooperativeThreads = properties.getJet().getCooperativeThreadCount();
        if (cooperativeThreads != null) {
            config.getJetConfig().setCooperativeThreadCount(cooperativeThreads);
        }
        // Make the member SRS-capable. The change-ring item is not zero-config serializable (its
        // heterogeneous row map defeats Compact), so its stream serializer is registered for ring storage
        // and Jet cross-vertex transport alike. The per-table change rings under srs.* are the SRS's only
        // hot buffer: bounded, in memory, with no time expiry (headroom backpressure guards unread
        // overwrites, not TTL) and no backups (single node).
        config.getSerializationConfig().addSerializerConfig(new SerializerConfig()
                .setTypeClass(SrsItem.class)
                .setImplementation(new SrsItemSerializer()));
        config.addRingBufferConfig(new RingbufferConfig("srs.*")
                .setCapacity(SRS_RING_CAPACITY)
                .setInMemoryFormat(InMemoryFormat.OBJECT)
                .setTimeToLiveSeconds(0)
                .setBackupCount(0));
        return config;
    }
}
