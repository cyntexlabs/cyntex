package io.cyntex.cli;

import java.net.URI;

/**
 * The CLI's transport seam to a running Cyntex server (rule R6: the CLI reaches services over HTTP
 * only). Kept minimal on purpose — one reachability probe for now; the connected verbs a later slice
 * adds their own methods. The interface exists so the connect logic is testable with a network-free
 * fake and the production {@link HttpControlPlaneClient} is swapped in only at the process entry point.
 */
interface ControlPlaneClient {

    /** Whether {@code GET {baseUrl}/healthz} answers 200; any I/O failure counts as not healthy. */
    boolean isHealthy(URI baseUrl);
}
