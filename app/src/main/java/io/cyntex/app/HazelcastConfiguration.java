package io.cyntex.app;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import io.cyntex.core.common.CyntexException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean(destroyMethod = "shutdown")
    HazelcastInstance hazelcastMember(HazelcastProperties properties) {
        Config config = memberConfig(properties);
        return startMember(() -> Hazelcast.newHazelcastInstance(config));
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
        return config;
    }
}
