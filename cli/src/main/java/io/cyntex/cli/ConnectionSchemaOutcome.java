package io.cyntex.cli;

/**
 * The outcome of a remote {@code GET /api/connections/{id}/schema}. Either the connection's latest
 * stored source model was found, no model is stored for it (a 404: it has never been discovered), the
 * request was refused with a coded reason, or the server could not be reached. Sealed so the caller
 * renders each branch without try/catch, mirroring the never-throw seam.
 */
sealed interface ConnectionSchemaOutcome {

    /** The connection's latest stored source model. */
    record Found(ConnectionSchema schema) implements ConnectionSchemaOutcome {
    }

    /** No source model is stored for the connection: it has never been discovered. */
    record Absent() implements ConnectionSchemaOutcome {
    }

    /** The server refused the read with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements ConnectionSchemaOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements ConnectionSchemaOutcome {
    }
}
