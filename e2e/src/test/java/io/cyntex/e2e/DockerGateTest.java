package io.cyntex.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate decides what a missing Docker daemon means, and the answer differs by where the build
 * runs. A specification tier whose whole reason to exist is running the real product must never
 * report green in CI without having run - a skip there is an absent gate wearing a passing badge.
 * On a developer machine the same absence is ordinary, and aborting is the honest outcome.
 */
class DockerGateTest {

    @Test
    void runsWhenDockerIsThere() {
        assertThat(DockerGate.decide(true, false)).isEqualTo(DockerGate.Decision.RUN);
        assertThat(DockerGate.decide(true, true)).isEqualTo(DockerGate.Decision.RUN);
    }

    @Test
    void skipsOnADeveloperMachineWithoutDocker() {
        assertThat(DockerGate.decide(false, false)).isEqualTo(DockerGate.Decision.SKIP);
    }

    @Test
    void failsRatherThanSkipsInCiWithoutDocker() {
        assertThat(DockerGate.decide(false, true)).isEqualTo(DockerGate.Decision.FAIL);
    }
}
