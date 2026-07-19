package io.cyntex.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate decides what missing real connector jars mean, and the answer turns on intent, the same
 * way {@link DockerGate} turns on where the build runs. A witness whose reason to exist is running two
 * real connectors must never report green having aborted on a typo'd or half-populated connectors
 * directory - a skip there is a broken gate wearing a passing badge. Naming no directory at all is the
 * ordinary developer default and aborts; naming one whose jars do not resolve is a gate the operator
 * meant to run and fails.
 */
class RealConnectorGateTest {

    @Test
    void runsWhenTheNamedDirectoryResolvesEveryJar() {
        assertThat(RealConnectorGate.decide(true, true)).isEqualTo(RealConnectorGate.Decision.RUN);
    }

    @Test
    void skipsWhenNoConnectorsDirectoryIsNamed() {
        assertThat(RealConnectorGate.decide(false, false)).isEqualTo(RealConnectorGate.Decision.SKIP);
    }

    @Test
    void namingNoDirectoryDominatesRatherThanFailing() {
        // jarsResolvable cannot really be true without a directory; the rule still says the absence of
        // intent skips rather than fails, so an accidental true here must not turn into a failure.
        assertThat(RealConnectorGate.decide(false, true)).isEqualTo(RealConnectorGate.Decision.SKIP);
    }

    @Test
    void failsRatherThanSkipsWhenTheNamedDirectoryDoesNotResolve() {
        assertThat(RealConnectorGate.decide(true, false)).isEqualTo(RealConnectorGate.Decision.FAIL);
    }
}
