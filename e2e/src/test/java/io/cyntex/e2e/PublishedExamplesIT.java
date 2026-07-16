package io.cyntex.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Runs every published example, on every tier.
 *
 * <p>The sweep is what makes an example real. Naming the examples here instead - one constant per test -
 * would leave two sets that nothing reconciles: the ones published under {@code examples/} and the ones
 * some test happens to name. A specification could then sit in the working tree, be parsed, be validated
 * against the schema, be read and copied by an author as a working sample, and never once be run. It
 * could name a read the runtime ignores or a step the product renamed and stay green forever, looking
 * exactly like the examples that do run. Discovering them is what forbids that: to publish one is to run
 * it.
 *
 * <p>Executing the specification is the assertion. Its awaits are bounded and read the real target
 * through the harness, so a run that returns is a run whose every await held; the executor throws
 * otherwise. What each example is for, and why its numbers are what they are, is written in the example
 * itself - it is the file an author opens.
 *
 * <p>Nothing here reads a count and compares it to a number this class knows. That check used to close
 * each run, guarding against a harness that resolved an alias to the wrong directory and counted the rows
 * it had seeded itself. It is a property of one piece of harness code, identical for every example, so it
 * is proven once and directly, without a pipeline.
 */
class PublishedExamplesIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(200);

    @TempDir
    private Path connectorJars;

    @TempDir
    private Path sourceDirectory;

    @TempDir
    private Path targetDirectory;

    private String previousConnectorsDir;

    @BeforeAll
    static void requireDocker() {
        DockerGate.require();
    }

    @BeforeEach
    void publishTheConnectorJar() {
        E2eConnectorJar.buildInto(connectorJars);
        previousConnectorsDir = System.setProperty("cyntex.e2e.connectors-dir", connectorJars.toString());
    }

    @AfterEach
    void restoreTheConnectorsDirectory() {
        if (previousConnectorsDir == null) {
            System.clearProperty("cyntex.e2e.connectors-dir");
        } else {
            System.setProperty("cyntex.e2e.connectors-dir", previousConnectorsDir);
        }
    }

    /** The fidelity axis, and the only thing that differs between the runs of one example. */
    private enum Tiers {

        IN_PROCESS(InProcessServer::start),
        REAL_PROCESS(RealProcessServer::start);

        private final Function<String, ServerHandle> launcher;

        Tiers(Function<String, ServerHandle> launcher) {
            this.launcher = launcher;
        }
    }

    static Stream<Arguments> everyPublishedExampleOnEveryTier() {
        return Examples.specifications().stream()
                .flatMap(specification -> Stream.of(Tiers.values())
                        .map(tier -> Arguments.of(specification, tier)));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("everyPublishedExampleOnEveryTier")
    void thePublishedExampleRuns(Path specification, Tiers tier) {
        Path workspace = specification.getParent();

        try (ServerHandle server = tier.launcher.apply(SharedMongo.replicaSetUrl(store(workspace, tier)));
                Endpoints files = new FileEndpoints()) {
            ControlPlane control = new ControlPlane(server.baseUrl());
            control.bootstrapAndLogin("e2e", "e2e-password");
            HttpTierBinding binding = new HttpTierBinding(
                    control, workspace, Map.of(E2eConnectorJar.CONNECTOR_ID, files), env());

            new E2eExecutor(binding, new FilePipelineLoader(workspace), TIMEOUT, POLL)
                    .execute(EnvelopeParser.parse(Examples.read(specification)));
        }
    }

    /** Mongo refuses a database name longer than this, and says so only once a run is already underway. */
    private static final int NAME_LIMIT = 63;

    /**
     * One database per example per tier. Sharing one would leave the previous example's resources behind,
     * and the driver reconciling them would dial the temporary directories of a run that has ended.
     *
     * <p>An example names itself as long as it likes, so the name is trimmed to fit and a digest of the
     * whole of it is appended - two examples that trim to the same prefix still get a database each.
     */
    private static String store(Path workspace, Tiers tier) {
        String head = "e2e_";
        String tail = "_" + tier.name().toLowerCase(Locale.ROOT);
        String name = workspace.getFileName().toString().toLowerCase(Locale.ROOT).replace('-', '_');
        int room = NAME_LIMIT - head.length() - tail.length();
        if (name.length() > room) {
            String digest = Integer.toHexString(name.hashCode());
            name = name.substring(0, room - digest.length() - 1) + "_" + digest;
        }
        return head + name + tail;
    }

    /**
     * The harness is the client, so the addresses the published references resolve to are its own. The two
     * directories are deliberately different: a sink names its target table after the source row's table,
     * so one directory would have a pipeline write back over the file the harness seeded, and a count would
     * then read the harness's own rows without a single row having crossed the product.
     */
    private UnaryOperator<String> env() {
        return Map.of(
                "SRC_DIR", sourceDirectory.toString(),
                "TGT_DIR", targetDirectory.toString())::get;
    }
}
