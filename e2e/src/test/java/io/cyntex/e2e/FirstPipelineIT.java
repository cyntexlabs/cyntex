package io.cyntex.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first specification that moves rows through a connector: a source directory, the product, a
 * target directory, and a count taken from the target by a reader that is not the product.
 *
 * <p>Everything between the two directories is the real thing - the artifact is registered through the
 * register verb, its class is resolved and loaded in isolation, the source model is discovered from it,
 * the target model and its key are derived from that discovery, the lifecycle verb drives a Jet job,
 * and the rows travel capture -> ring -> DAG -> sink. Only the stores at the ends are directories.
 *
 * <p>The two directories are deliberately different ones. A sink names its target table after the
 * source row's table, so a single directory would have the pipeline write {@code orders.csv} back over
 * the file the harness seeded - and the count would then read the harness's own rows and pass without a
 * single row having crossed the product.
 *
 * <p>The specification and the three resources it names are read from the checked-in example rather
 * than written here. An example is what an author copies before writing their own, so the one that is
 * published has to be the one that runs: a sample kept beside the executor rather than inside it drifts
 * the moment the executor moves, and it drifts looking authoritative.
 */
class FirstPipelineIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(200);

    /**
     * What the published example keeps, not what it seeds: the filter admits even ids, so this is
     * |{2,4,6}| of the six rows the source ends up holding. Counted rather than derived - it is not a
     * function of the seed size, and a seed changed without recounting it would assert an arithmetic
     * that no longer holds.
     */
    private static final long KEPT_TOTAL = 3;

    /** The published example, read from the working tree - these bytes are the ones under test. */
    private static final Path WORKSPACE = Examples.workspace("rows-cross-from-a-source-file-to-a-target-file");

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

    /** The fidelity axis, and the only thing that differs between the runs below. */
    private enum Tiers {

        IN_PROCESS(InProcessServer::start),
        REAL_PROCESS(RealProcessServer::start);

        private final Function<String, ServerHandle> launcher;

        Tiers(Function<String, ServerHandle> launcher) {
            this.launcher = launcher;
        }
    }

    /**
     * Seeded rows reach the target, and rows produced while the pipeline runs follow them.
     *
     * <p>The count is the assertion, and it is the only one that could be. A pipeline whose connector
     * emits nothing still reaches {@code RUNNING}, so a specification that awaited the state would pass
     * over an empty pipeline; and the target file does not exist at all until the product creates it, so
     * a count that reaches two is two rows that were read, carried and written.
     *
     * <p>Every number here is smaller than the rows that were seeded, and that gap is the point: the
     * filter admits even ids, so a transform quietly reduced to an identity would carry four and then
     * six. Neither is ever two or three, and the target only grows, so such a run cannot reach either
     * number late - it fails rather than lags.
     *
     * <p>Three, not five: the tail has no offset to resume from and replays the four seeded rows on top
     * of the snapshot's, so the sink is handed five rows - two, two again, and one - and keeps three.
     * That is the overlap the product's snapshot-to-cdc seam leaves, and the upsert absorbing it is what
     * this number asserts. The key it absorbs on is derived from the discovered source model, so a run
     * that discovered nothing would append instead and climb two, four, five - never resting on three.
     */
    @ParameterizedTest
    @EnumSource(Tiers.class)
    void rowsCrossFromASourceFileToATargetFile(Tiers tier) {
        String store = SharedMongo.replicaSetUrl("e2e_first_" + tier.name().toLowerCase() + "_store");

        try (ServerHandle server = tier.launcher.apply(store);
                Endpoints files = new FileEndpoints()) {
            ControlPlane control = new ControlPlane(server.baseUrl());
            control.bootstrapAndLogin("e2e", "e2e-password");
            HttpTierBinding binding = new HttpTierBinding(
                    control, WORKSPACE, Map.of(E2eConnectorJar.CONNECTOR_ID, files), env());

            new E2eExecutor(binding, new FilePipelineLoader(WORKSPACE), TIMEOUT, POLL)
                    .execute(EnvelopeParser.parse(Examples.read(WORKSPACE.resolve("spec.e2e.yml"))));

            // The awaits above counted through the binding, which finds a table's directory by reading the
            // resource the specification applied. This reads the target directory by the path this test
            // chose, so a binding that had been counting the source's own seeded file all along - the one
            // way every count above could hold without a row moving - cannot reach here.
            assertThat(files.count(targetDirectory.toString(), "orders")).isEqualTo(KEPT_TOTAL);
        }
    }

    /** The harness is the client, so the addresses the published references resolve to are its own. */
    private UnaryOperator<String> env() {
        return Map.of(
                "SRC_DIR", sourceDirectory.toString(),
                "TGT_DIR", targetDirectory.toString())::get;
    }
}
