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
 * The example that holds the snapshot half of a snapshot-and-cdc read to account.
 *
 * <p>The sibling example moves rows and counts them, but it cannot tell where they came from. This
 * connector's tail has no offset to resume from and replays the table from its beginning, so the
 * snapshot is redundant to it: delete the snapshot phase, ask for changes only, and the tail alone
 * still delivers every seeded row and every count still holds. The read that the first example names is
 * therefore only half witnessed by it - the tail half.
 *
 * <p>This one closes that by filtering on where a row came from rather than what it holds. A snapshot
 * read is the only row the product ever marks {@code op=r}: it is minted in one place, on the batch-read
 * path, and the change ring refuses to carry one at all - constructing an item with that op throws. So a
 * row reaching the target here is a row the snapshot phase produced, and asking for changes only leaves
 * the predicate nothing to admit: the target file is never created and the count never leaves zero.
 *
 * <p>What it witnesses is a conjunction, and worth naming rather than discovering later: that the
 * snapshot ran <em>and</em> that the filter still filters. A filter port reduced to an identity would
 * pass this example under either read, because the tail's replay would carry the same ids to the same
 * count. The sibling example is what forbids that - its counts are below what a degraded filter carries -
 * so the two are load-bearing together and neither is sufficient alone.
 */
class SnapshotHalfIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(200);

    /** Every seeded row is a snapshot read, so the target holds all of them and nothing else. */
    private static final long SEEDED = 3;

    private static final Path WORKSPACE = Examples.workspace("the-snapshot-half-reaches-the-target");

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

    @ParameterizedTest
    @EnumSource(Tiers.class)
    void rowsTheSnapshotReadReachTheTarget(Tiers tier) {
        String store = SharedMongo.replicaSetUrl("e2e_snap_" + tier.name().toLowerCase() + "_store");

        try (ServerHandle server = tier.launcher.apply(store);
                Endpoints files = new FileEndpoints()) {
            ControlPlane control = new ControlPlane(server.baseUrl());
            control.bootstrapAndLogin("e2e", "e2e-password");
            HttpTierBinding binding = new HttpTierBinding(
                    control, WORKSPACE, Map.of(E2eConnectorJar.CONNECTOR_ID, files), env());

            new E2eExecutor(binding, new FilePipelineLoader(WORKSPACE), TIMEOUT, POLL)
                    .execute(EnvelopeParser.parse(Examples.read(WORKSPACE.resolve("spec.e2e.yml"))));

            // Read by the path this test chose rather than the one the binding resolved, so a count that
            // had been reading the source's own seeded file cannot reach here.
            assertThat(files.count(targetDirectory.toString(), "orders")).isEqualTo(SEEDED);
        }
    }

    /** The harness is the client, so the addresses the published references resolve to are its own. */
    private UnaryOperator<String> env() {
        return Map.of(
                "SRC_DIR", sourceDirectory.toString(),
                "TGT_DIR", targetDirectory.toString())::get;
    }
}
