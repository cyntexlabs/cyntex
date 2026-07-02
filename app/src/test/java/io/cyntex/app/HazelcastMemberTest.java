package io.cyntex.app;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

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
}
