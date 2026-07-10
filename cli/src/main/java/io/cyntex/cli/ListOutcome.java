package io.cyntex.cli;

import java.util.List;

/**
 * The outcome of a remote {@code GET /api/artifacts}. Either the list read returned the stored artifacts
 * (possibly empty), the request was refused with a coded reason, or the server could not be reached.
 * Sealed so the caller renders each branch without try/catch, mirroring the never-throw seam.
 */
sealed interface ListOutcome {

    /** The stored artifacts the list returned, in server order (possibly empty). */
    record Listed(List<RemoteArtifact> artifacts) implements ListOutcome {
        public Listed {
            artifacts = List.copyOf(artifacts);
        }
    }

    /** The server refused the read with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements ListOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements ListOutcome {
    }
}
