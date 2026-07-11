package io.cyntex.cli;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * The CLI's transport seam to a running Cyntex server (rule R6: the CLI reaches services over HTTP
 * only). A reachability probe, the authentication call, and the connected artifact verbs (apply / get /
 * list), each authenticated by a bearer credential. Every call resolves to a result rather than throwing,
 * so the caller walks seeds, fails over and renders outcomes without try/catch. The interface exists so
 * the REPL logic is testable with a network-free fake and the production {@link HttpControlPlaneClient}
 * is swapped in only at the process entry point.
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

    /**
     * Applies a batch of authored drafts via {@code POST {baseUrl}/api/artifacts:apply}, authenticated by
     * the bearer {@code credential}: the per-artifact results on success, a coded rejection when the server
     * refuses (a validation failure is a {@code dsl.*} code), or unreachable on any I/O failure. Never throws.
     */
    ApplyOutcome apply(URI baseUrl, String credential, List<LocalDraft> drafts);

    /**
     * Reads one artifact by id via {@code GET {baseUrl}/api/artifacts/{id}}, authenticated by the bearer
     * {@code credential}: found with its canonical form, absent on a 404, a coded rejection, or unreachable
     * on any I/O failure. Never throws.
     */
    GetOutcome get(URI baseUrl, String credential, String id);

    /**
     * Lists stored artifacts via {@code GET {baseUrl}/api/artifacts}, optionally filtered by {@code kind}
     * ({@code null} = all), authenticated by the bearer {@code credential}: the artifacts on success, a
     * coded rejection, or unreachable on any I/O failure. Never throws.
     */
    ListOutcome list(URI baseUrl, String credential, String kind);

    /**
     * Runs a connection test via {@code POST {baseUrl}/api/connections:test}, authenticated by the bearer
     * {@code credential}: the request carries the connection {@code id} (the key its result is stored under),
     * the {@code connectorId} it configures, and the {@code settings} to run that connector with. Returns the
     * structured report on success (pass or fail alike), a coded rejection when the server refuses, or
     * unreachable on any I/O failure. Never throws.
     */
    ConnectionTestOutcome test(
            URI baseUrl, String credential, String id, String connectorId, Map<String, Object> settings);
}
