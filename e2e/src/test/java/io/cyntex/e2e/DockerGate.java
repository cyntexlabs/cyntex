package io.cyntex.e2e;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;

/**
 * Decides what an unavailable Docker daemon means for a specification run.
 *
 * <p>The usual annotation for this ({@code @Testcontainers(disabledWithoutDocker = true)}) answers
 * "skip" everywhere, which is wrong in exactly one place that matters: a tier whose reason to exist
 * is running the real product would report a green build in CI having run nothing. So the answer is
 * split by environment - a developer without Docker gets an honest abort, CI without Docker gets a
 * failure.
 */
final class DockerGate {

    /** Set on every GitHub Actions runner, and by convention on other CI services. */
    private static final String CI_ENV = "CI";

    private DockerGate() {
    }

    enum Decision {

        /** Docker answers: run the specification against real containers. */
        RUN,

        /** No Docker, no CI: a developer machine, where absence is ordinary. */
        SKIP,

        /** No Docker in CI: the gate is absent, and pretending otherwise is the bug. */
        FAIL
    }

    static Decision decide(boolean dockerAvailable, boolean ci) {
        if (dockerAvailable) {
            return Decision.RUN;
        }
        return ci ? Decision.FAIL : Decision.SKIP;
    }

    /** Applies the decision to the calling test: returns, aborts, or fails. */
    static void require() {
        switch (decide(dockerAvailable(), ci())) {
            case RUN -> {
            }
            case SKIP -> Assumptions.abort(
                    "no Docker daemon: skipping a specification that needs real endpoints. "
                            + "On Docker 29 the daemon rejects the API version the client negotiates by "
                            + "default; pass -Dapi.version=1.44 to run these locally.");
            case FAIL -> throw new AssertionError(
                    "no Docker daemon in CI: specifications must run here, and skipping them would "
                            + "report a green build that verified nothing");
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean ci() {
        return "true".equals(System.getenv(CI_ENV));
    }
}
