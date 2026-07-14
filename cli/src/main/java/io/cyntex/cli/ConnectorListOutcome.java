package io.cyntex.cli;

import java.util.List;

/**
 * The outcome of a remote {@code GET /api/connectors}. Either the list read returned the connectors the
 * online catalog exposes (the bundled snapshot union the registered rows, possibly empty), the request
 * was refused with a coded reason, or the server could not be reached. Sealed so the caller renders each
 * branch without try/catch, mirroring the never-throw seam.
 */
sealed interface ConnectorListOutcome {

    /** The connectors the list returned, in server order (possibly empty). */
    record Listed(List<CatalogConnector> connectors) implements ConnectorListOutcome {
        public Listed {
            connectors = List.copyOf(connectors);
        }
    }

    /** The server refused the read with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements ConnectorListOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements ConnectorListOutcome {
    }
}
