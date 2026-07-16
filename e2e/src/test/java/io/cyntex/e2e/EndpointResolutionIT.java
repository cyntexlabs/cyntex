package io.cyntex.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the harness to the one promise every count in every specification rests on: that an alias reaches
 * the endpoint its own resource names, and no other.
 *
 * <p>A specification says {@code count: { tgt_file.orders: 3 }} and trusts the harness to know where
 * {@code tgt_file} lives. The harness learns that by reading the resource it applied. Were that lookup to
 * collapse two aliases onto one address, a specification could count the rows the harness itself seeded
 * into the source and pass without a single row having crossed the product - every await green, the
 * product never consulted. That is the one way a whole suite of specifications can be wrong at once.
 *
 * <p>So it is checked here rather than at the end of a pipeline run. The lookup is one piece of harness
 * code shared by every specification, so a run per example proves the same thing repeatedly and proves it
 * late; and a run that never started a pipeline has no count to disagree with, which is exactly when a
 * wrong address would go unnoticed. Two aliases, two directories this test chose, two different row
 * counts, and no pipeline at all: the seeds are read back by path, not by alias, so a lookup that sent
 * both to one directory cannot survive.
 *
 * <p>One tier, deliberately. The lookup runs entirely in the harness before the product is dialled, so it
 * cannot differ by how the server was launched.
 */
class EndpointResolutionIT {

    private static final long INTO_THE_SOURCE = 4;
    private static final long INTO_THE_TARGET = 7;

    @TempDir
    private Path workspace;

    @TempDir
    private Path sourceDirectory;

    @TempDir
    private Path targetDirectory;

    @BeforeAll
    static void requireDocker() {
        DockerGate.require();
    }

    @Test
    void eachAliasReachesTheEndpointItsOwnResourceNames() {
        writeWorkspace();

        try (ServerHandle server = InProcessServer.start(SharedMongo.replicaSetUrl("e2e_endpoints_store"));
                Endpoints files = new FileEndpoints()) {
            ControlPlane control = new ControlPlane(server.baseUrl());
            control.bootstrapAndLogin("e2e", "e2e-password");
            HttpTierBinding binding = new HttpTierBinding(
                    control, workspace, Map.of(E2eConnectorJar.CONNECTOR_ID, files), env());
            binding.applyResources(List.of("src_file.cyn.yml", "tgt_file.cyn.yml"));

            binding.seed(new TableAlias("src_file", "orders"), INTO_THE_SOURCE);
            binding.seed(new TableAlias("tgt_file", "orders"), INTO_THE_TARGET);

            // Read back by the paths this test handed out, so the harness cannot agree with itself: a
            // lookup that resolved both aliases to one directory would have the second seed overwrite the
            // first, and neither directory would hold what it was asked for.
            assertThat(files.count(sourceDirectory.toString(), "orders")).isEqualTo(INTO_THE_SOURCE);
            assertThat(files.count(targetDirectory.toString(), "orders")).isEqualTo(INTO_THE_TARGET);

            // And the counts a specification would read resolve the same way round.
            assertThat(binding.count(new TableAlias("src_file", "orders"))).isEqualTo(INTO_THE_SOURCE);
            assertThat(binding.count(new TableAlias("tgt_file", "orders"))).isEqualTo(INTO_THE_TARGET);
        }
    }

    /** Two endpoints on one connector, distinguished only by the address each carries. */
    private void writeWorkspace() {
        write("src_file.cyn.yml", """
                version: cyntex/v1
                kind: source
                id: src_file
                connector: e2e_file
                config: { uri: "${SRC_DIR}" }
                mode: cdc
                tables: [ orders ]
                """);
        write("tgt_file.cyn.yml", """
                version: cyntex/v1
                kind: source
                id: tgt_file
                connector: e2e_file
                config: { uri: "${TGT_DIR}" }
                """);
    }

    private UnaryOperator<String> env() {
        return Map.of(
                "SRC_DIR", sourceDirectory.toString(),
                "TGT_DIR", targetDirectory.toString())::get;
    }

    private void write(String name, String content) {
        try {
            Files.writeString(workspace.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
