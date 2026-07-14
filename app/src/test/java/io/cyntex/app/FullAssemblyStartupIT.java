package io.cyntex.app;

import com.hazelcast.core.HazelcastInstance;
import io.cyntex.adapters.pdk.ConnectorProvisioner;
import io.cyntex.control.core.ApplyService;
import io.cyntex.runtime.engine.Engine;
import io.cyntex.runtime.scheduler.PipelineConverger;
import io.cyntex.spi.store.SrsMetaStore;
import io.cyntex.spi.store.StorePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole platform assembled as one JVM process — the L1 {@code --role=all} shape — brought up over a real
 * store. Booting the real {@code @SpringBootApplication} with the store enabled activates every assembly
 * configuration at once, so all six architectural rings come up in a single application context: the store
 * bridge, the control-core plane over it, the PDK connector plane, the embedded Hazelcast member, the Jet
 * engine over that member, and the desired-to-actual convergence loop. This witnesses that the independently
 * developed rings collapse into one coherent process and that the liveness probe answers over the full
 * assembly — a scenario no other test co-boots: the control-plane assembly checks import only the store and
 * control configurations (no member/engine/convergence), and every full-application boot elsewhere runs with
 * the store disabled, which gates four of the six rings off. Skipped automatically where Docker is absent, so
 * a Docker-less build stays green.
 */
@Testcontainers(disabledWithoutDocker = true)
class FullAssemblyStartupIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

    @Container
    private static final MongoDBContainer REPLICA_SET = new MongoDBContainer(MONGO_IMAGE);

    private ConfigurableApplicationContext context;

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void roleAllBringsUpEverySixRingBeanInOneJvmAndServesHealth() {
        int port = startFullAssembly();

        // The one context is live, and every architectural ring has a working bean in it: booting the whole
        // application with the store enabled is the --role=all assembly, so these are wired together rather
        // than in the hand-picked subsets the other store-enabled checks assemble.
        assertThat(context.isRunning()).isTrue();
        assertThat(context.getBean(StorePort.class)).isNotNull();            // adapters -> spi.store bridge
        assertThat(context.getBean(SrsMetaStore.class)).isNotNull();         // the SRS meta store
        assertThat(context.getBean(ApplyService.class)).isNotNull();         // a control-core service
        assertThat(context.getBean(ConnectorProvisioner.class)).isNotNull(); // the adapter-pdk connector plane
        assertThat(context.getBean(Engine.class)).isNotNull();               // the Jet engine over the member
        assertThat(context.getBean(PipelineConverger.class)).isNotNull();    // desired -> actual convergence
        assertThat(context.getBean(ConvergenceDriver.class)).isNotNull();    // the scheduled convergence driver

        // The embedded Hazelcast member is the runtime backbone every store-enabled subset test omits; under
        // the full assembly it comes up and is running.
        HazelcastInstance member = context.getBean(HazelcastInstance.class);
        assertThat(member.getLifecycleService().isRunning()).isTrue();

        // The liveness probe answers over the full assembly, not merely the control-plane-only subset.
        RestClient client = RestClient.create("http://localhost:" + port);
        String health = client.get().uri("/healthz").retrieve().body(String.class);
        assertThat(health).isEqualTo("ok");
    }

    private int startFullAssembly(String... extraProperties) {
        // A per-run database on the shared class container keeps runs independent.
        String database = "assembly_all_" + Long.toUnsignedString(System.nanoTime(), 16);
        List<String> properties = new ArrayList<>(List.of(
                "server.port=0",
                "cyntex.store.mongo.enabled=true",
                "cyntex.store.mongo.uri=" + REPLICA_SET.getReplicaSetUrl(database),
                // the container speaks plaintext; TLS is opt-in, so no flag is needed here
                "cyntex.store.mongo.server-selection-timeout=5s"));
        properties.addAll(List.of(extraProperties));
        context = new SpringApplicationBuilder(Bootstrap.class)
                .properties(properties.toArray(String[]::new))
                .run();
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }
}
