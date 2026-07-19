package io.cyntex.e2e;

import org.junit.jupiter.api.Assumptions;

import java.util.Arrays;

/**
 * Decides what missing real connector jars mean for a witness that drives real connectors, the same
 * way {@link DockerGate} decides about a missing Docker daemon. A silently skipped run is the failure
 * this closes: a gated witness that aborts on a typo'd or half-populated connectors directory reports
 * a green build having proven nothing. So intent is the discriminator - no directory named at all is
 * the ordinary developer default and aborts, but a directory named whose jars do not all resolve is a
 * broken gate the operator meant to run and fails.
 */
final class RealConnectorGate {

    private RealConnectorGate() {
    }

    enum Decision {

        /** The named directory resolves every requested jar: drive the real connectors. */
        RUN,

        /** No directory named: a developer machine, where absence is ordinary. */
        SKIP,

        /** A directory was named but its jars do not all resolve: the gate is broken, not absent. */
        FAIL
    }

    static Decision decide(boolean directoryNamed, boolean jarsResolvable) {
        if (!directoryNamed) {
            return Decision.SKIP;
        }
        return jarsResolvable ? Decision.RUN : Decision.FAIL;
    }

    /**
     * Applies the decision for the given connector ids to the calling test: returns, aborts, or fails.
     * Reads the process-global connectors directory a peer specification may set for its own run; the
     * e2e module runs test classes sequentially in one fork, so that peer has restored it before this
     * gate reads it. Enabling in-JVM test parallelism would break that and is why it is not.
     */
    static void require(String... connectorIds) {
        boolean directoryNamed = ConnectorJars.directoryNamed();
        switch (decide(directoryNamed, directoryNamed && allResolvable(connectorIds))) {
            case RUN -> {
            }
            case SKIP -> Assumptions.abort(
                    "no -Dcyntex.e2e.connectors-dir: skipping a witness that needs real connector jars");
            case FAIL -> throw new AssertionError(
                    "-Dcyntex.e2e.connectors-dir is set but " + Arrays.toString(connectorIds)
                            + " did not all resolve there: a witness meant to run must not skip on a broken gate");
        }
    }

    private static boolean allResolvable(String... connectorIds) {
        for (String connectorId : connectorIds) {
            if (!ConnectorJars.resolves(connectorId)) {
                return false;
            }
        }
        return true;
    }
}
