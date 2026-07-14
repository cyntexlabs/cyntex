package io.cyntex.cli;

/**
 * The outcome of a remote {@code POST /api/connectors:register}. Either the server registered the
 * uploaded artifact (newly, or found it already present) and returned what was registered, the request
 * was refused with a coded reason (a bad artifact, an id conflict), or the server could not be reached.
 * Sealed so the caller renders each branch without try/catch, mirroring the never-throw seam.
 */
sealed interface ConnectorRegisterOutcome {

    /** The server registered the artifact and returned the registration. */
    record Registered(RegisteredConnector connector) implements ConnectorRegisterOutcome {
    }

    /** The server refused the request with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements ConnectorRegisterOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements ConnectorRegisterOutcome {
    }
}
