package io.cyntex.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real-process tier's fidelity claim, and the reason the framework carries two bindings at all:
 * what answers here is the jar that ships, launched the way a user launches it, in a JVM this test
 * does not share. Everything the in-process tier can hide - the fat-jar's nested layout, a resource
 * that only resolves from a directory classpath, a startup that only works because the test already
 * warmed it - fails here instead of in production.
 *
 * <p>The calls below are deliberately the same ones {@link InProcessServerIT} makes. If a driver
 * method needed a tier-specific spelling, the claim that one specification runs on both tiers would
 * already be false.
 */
class RealProcessServerIT {

    private static final String SOURCE = """
            version: cyntex/v1
            kind: source
            id: e2e_probe
            connector: mongodb
            config: { uri: "mongodb://127.0.0.1:27017/e2e" }
            """;

    @BeforeAll
    static void requireDocker() {
        DockerGate.require();
    }

    @Test
    void launchesTheShippedJarAndServesTheSameSurfaceTheInProcessTierDoes() {
        try (ServerHandle server = RealProcessServer.start(SharedMongo.replicaSetUrl("e2e_realproc"))) {
            ControlPlane control = new ControlPlane(server.baseUrl());
            assertThat(control.healthy()).isTrue();

            control.bootstrapAndLogin("e2e", "e2e-password");
            control.apply(Map.of("e2e_probe.cyn.yml", SOURCE));

            assertThat(control.artifactIds()).contains("e2e_probe");
        }
    }

    @Test
    void stopsTheServerItLaunchedSoTheNextRunIsNotServedByThisOne() {
        ServerHandle server = RealProcessServer.start(SharedMongo.replicaSetUrl("e2e_realproc_stop"));
        ControlPlane control = new ControlPlane(server.baseUrl());
        assertThat(control.healthy()).isTrue();

        server.close();

        assertThat(control.healthy()).isFalse();
    }
}
