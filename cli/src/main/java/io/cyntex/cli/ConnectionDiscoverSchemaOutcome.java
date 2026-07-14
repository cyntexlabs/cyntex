package io.cyntex.cli;

/**
 * The outcome of a remote {@code POST /api/connections:discover-schema}. Either the server ran the
 * discovery and returned the discovered model, the request was refused with a coded reason, or the
 * server could not be reached. Sealed so the caller renders each branch without try/catch, mirroring
 * the never-throw seam.
 */
sealed interface ConnectionDiscoverSchemaOutcome {

    /** The server ran the discovery and returned the discovered model. */
    record Discovered(ConnectionSchema schema) implements ConnectionDiscoverSchemaOutcome {
    }

    /** The server refused the request with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements ConnectionDiscoverSchemaOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements ConnectionDiscoverSchemaOutcome {
    }
}
