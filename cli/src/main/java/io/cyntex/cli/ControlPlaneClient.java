package io.cyntex.cli;

import java.net.URI;
import java.util.List;

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
     * Issues a pipeline lifecycle verb ({@code start} / {@code stop} / {@code pause} / {@code resume}) via
     * {@code POST {baseUrl}/api/pipelines/{pipelineId}:{verb}}, authenticated by the bearer
     * {@code credential}: the pipeline's new desired state on success, a coded rejection when the server
     * refuses (an unknown pipeline, a forbidden transition, or a stale revision), or unreachable on any I/O
     * failure. Never throws.
     */
    LifecycleOutcome lifecycle(URI baseUrl, String credential, String pipelineId, String verb);

    /**
     * Reads a pipeline's lifecycle state via {@code GET {baseUrl}/api/pipelines/{pipelineId}/status},
     * authenticated by the bearer {@code credential}: the state on success, a coded rejection when the
     * server refuses (a pipeline with no published observation is {@code monitor.no-observation}), or
     * unreachable on any I/O failure. Never throws.
     */
    StatusOutcome status(URI baseUrl, String credential, String pipelineId);

    /**
     * Reads a pipeline's open map of run statistics via {@code GET {baseUrl}/api/pipelines/{pipelineId}/metrics},
     * authenticated by the bearer {@code credential}: the metrics on success (empty when no source is wired
     * yet), a coded rejection when the server refuses, or unreachable on any I/O failure. Never throws.
     */
    MetricsOutcome metrics(URI baseUrl, String credential, String pipelineId);

    /**
     * Reads a pipeline's per-table initial-load progress via
     * {@code GET {baseUrl}/api/pipelines/{pipelineId}/snapshot}, authenticated by the bearer
     * {@code credential}: the per-table progress on success (empty outside a snapshot phase), a coded
     * rejection when the server refuses, or unreachable on any I/O failure. Never throws.
     */
    SnapshotOutcome snapshot(URI baseUrl, String credential, String pipelineId);

    /**
     * Reads a pipeline's recent log lines via {@code GET {baseUrl}/api/pipelines/{pipelineId}/logs},
     * authenticated by the bearer {@code credential}: the tail on success (empty when the pipeline has
     * logged nothing on the served node), a coded rejection when the server refuses, or unreachable on any
     * I/O failure. Never throws.
     */
    LogsOutcome logs(URI baseUrl, String credential, String pipelineId);
}
