package io.cyntex.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The claim the two bindings exist to make: one specification, unchanged, runs on every tier.
 *
 * <p>This is checked by running the same YAML through the same executor twice, once per tier, rather
 * than by asserting the bindings look alike. A specification that passed on one tier and needed so
 * much as a different word on the other would fail here, which is the only place that promise can be
 * kept honest.
 *
 * <p>What it deliberately does not do is move data through a connector: no connector jar is
 * available to the build, so a specification that started a pipeline here would be testing the
 * absence of one. This checks what the binding itself owns - provisioning, endpoints, readings.
 */
class TierBindingsIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(200);

    @TempDir
    private Path workspace;

    @BeforeAll
    static void requireDocker() {
        DockerGate.require();
    }

    /** The fidelity axis, and the only thing that differs between the runs below. */
    private enum Tiers {

        IN_PROCESS(InProcessServer::start),
        REAL_PROCESS(RealProcessServer::start);

        private final Function<String, ServerHandle> launcher;

        Tiers(Function<String, ServerHandle> launcher) {
            this.launcher = launcher;
        }
    }

    @ParameterizedTest
    @EnumSource(Tiers.class)
    void theSameSpecificationRunsOnEveryTier(Tiers tier) {
        String database = "e2e_both_" + tier.name().toLowerCase();
        String endpointUri = SharedMongo.replicaSetUrl(database);
        writeWorkspace(endpointUri);

        try (ServerHandle server = tier.launcher.apply(SharedMongo.replicaSetUrl(database + "_store"));
                MongoEndpoints endpoints = new MongoEndpoints()) {
            ControlPlane control = new ControlPlane(server.baseUrl());
            control.bootstrapAndLogin("e2e", "e2e-password");
            HttpTierBinding binding = new HttpTierBinding(control, workspace, endpoints);

            new E2eExecutor(binding, new FilePipelineLoader(workspace), TIMEOUT, POLL)
                    .execute(EnvelopeParser.parse(specification()));

            // The counts above move - three seeded, two more inserted - so a binding that answered
            // with a constant, or from its own record rather than the database, cannot satisfy both
            // assertions. This last read confirms the rows are really there, through a driver the
            // binding does not own.
            assertThat(endpoints.count(endpointUri, "orders")).isEqualTo(5L);
            assertThat(control.artifactIds()).contains("tgt_mongo", "e2e_pipeline");
        }
    }

    @ParameterizedTest
    @EnumSource(Tiers.class)
    void everyTierRefusesToReadAnEndpointTheSpecificationNeverApplied(Tiers tier) {
        String database = "e2e_unapplied_" + tier.name().toLowerCase();
        writeWorkspace(SharedMongo.replicaSetUrl(database));

        try (ServerHandle server = tier.launcher.apply(SharedMongo.replicaSetUrl(database + "_store"));
                MongoEndpoints endpoints = new MongoEndpoints()) {
            ControlPlane control = new ControlPlane(server.baseUrl());
            control.bootstrapAndLogin("e2e", "e2e-password");
            HttpTierBinding binding = new HttpTierBinding(control, workspace, endpoints);

            assertThatThrownBy(() -> binding.count(new TableAlias("never_applied", "orders")))
                    .isInstanceOf(EnvelopeException.class)
                    .hasMessageContaining("no source applied for never_applied");
        }
    }

    /** The specification under test: identical text for both tiers, by construction. */
    private String specification() {
        return """
                name: both-tiers-see-the-same-endpoint
                tier: smoke
                setup:
                  apply: [tgt_mongo.cyn.yml, pipeline.cyn.yml]
                pipeline: pipeline.cyn.yml
                seed:
                  tgt_mongo.orders: { rows: 3 }
                steps:
                  - assert: { count: { tgt_mongo.orders: 3 } }
                  - cdc: { tgt_mongo.orders: insert 2 }
                  - assert: { count: { tgt_mongo.orders: 5 } }
                """;
    }

    private void writeWorkspace(String endpointUri) {
        // The endpoint's address is only known once the container is up, so the resource carries the
        // real URI rather than a placeholder: the product has no interpolation to resolve one.
        write("tgt_mongo.cyn.yml", """
                version: cyntex/v1
                kind: source
                id: tgt_mongo
                connector: mongodb
                config: { uri: "%s" }
                """.formatted(endpointUri));
        write("pipeline.cyn.yml", """
                version: cyntex/v1
                kind: pipeline
                id: e2e_pipeline
                source: tgt_mongo
                serve:
                  from: /.*/
                  sync:
                    - source: tgt_mongo
                """);
    }

    private void write(String name, String content) {
        try {
            Files.writeString(workspace.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
