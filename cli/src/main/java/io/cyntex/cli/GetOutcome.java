package io.cyntex.cli;

/**
 * The outcome of a remote {@code GET /api/artifacts/{id}}. Either the artifact was found, no artifact is
 * stored under that id (a 404), the request was refused with a coded reason, or the server could not be
 * reached. Sealed so the caller renders each branch without try/catch, mirroring the never-throw seam.
 */
sealed interface GetOutcome {

    /** The artifact exists in the truth layer. */
    record Found(RemoteArtifact artifact) implements GetOutcome {
    }

    /** No artifact is stored under the requested id. */
    record Absent() implements GetOutcome {
    }

    /** The server refused the read with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements GetOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements GetOutcome {
    }
}
