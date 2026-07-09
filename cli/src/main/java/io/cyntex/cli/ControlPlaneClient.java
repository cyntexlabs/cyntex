package io.cyntex.cli;

import java.net.URI;

/**
 * The CLI's transport seam to a running Cyntex server (rule R6: the CLI reaches services over HTTP
 * only). Kept minimal on purpose — a reachability probe and the authentication call for now; the
 * connected verbs a later slice adds their own methods. The interface exists so the connect / login
 * logic is testable with a network-free fake and the production {@link HttpControlPlaneClient} is
 * swapped in only at the process entry point.
 */
interface ControlPlaneClient {

    /** Whether {@code GET {baseUrl}/healthz} answers 200; any I/O failure counts as not healthy. */
    boolean isHealthy(URI baseUrl);

    /**
     * Verifies a username / password via {@code POST {baseUrl}/auth/login} and returns the outcome: a
     * bearer token on success, a coded rejection when the server refuses, or unreachable on any I/O
     * failure. Never throws.
     */
    LoginOutcome login(URI baseUrl, String username, String password);
}
