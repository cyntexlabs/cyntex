package io.cyntex.cli;

import io.cyntex.core.common.JsonReader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The production {@link ControlPlaneClient}, backed by the JDK HTTP client (no third-party
 * dependency, so rule R6 holds and the native image needs no extra metadata). Probes and calls are
 * given a short connect and request timeout so an unreachable seed fails fast, and every failure mode —
 * connection refused, timeout, unknown host, or a malformed / unsupported base URL — resolves to a
 * "not healthy" / "unreachable" result rather than throwing, so the caller can walk the seed list and
 * render outcomes without try/catch.
 */
final class HttpControlPlaneClient implements ControlPlaneClient {

    /** Short enough that an unreachable seed does not stall the connect walk. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private HttpClient httpClient;

    /** The client is built lazily so constructing a REPL that never connects costs nothing. */
    private HttpClient client() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        }
        return httpClient;
    }

    @Override
    public boolean isHealthy(URI baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl, "/healthz"))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = client().send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            // A malformed / unsupported base URL (e.g. no host) throws IllegalArgumentException when
            // the request is built; that, like an I/O failure, is simply "not healthy", never thrown.
            return false;
        }
    }

    @Override
    public LoginOutcome login(URI baseUrl, String username, String password) {
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("username", username);
            payload.put("password", password);
            HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl, "/auth/login"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonOut.write(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                String token = stringField(response.body(), "token");
                return token == null || token.isBlank()
                        ? new LoginOutcome.Unreachable()   // a 200 with no token is not a usable success
                        : new LoginOutcome.Success(token);
            }
            Rejection r = rejection(response.body(), "Login was refused by the server.");
            return new LoginOutcome.Rejected(r.code(), r.message());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LoginOutcome.Unreachable();
        } catch (IOException | RuntimeException e) {
            return new LoginOutcome.Unreachable();
        }
    }

    @Override
    public ApplyOutcome apply(URI baseUrl, String credential, List<LocalDraft> drafts) {
        try {
            HttpRequest request = authed(baseUrl, "/api/artifacts:apply", credential)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(applyBody(drafts), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                return new ApplyOutcome.Applied(applyItems(response.body()));
            }
            Rejection r = rejection(response.body(), "The server refused the apply.");
            return new ApplyOutcome.Rejected(r.code(), r.message());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ApplyOutcome.Unreachable();
        } catch (IOException | RuntimeException e) {
            return new ApplyOutcome.Unreachable();
        }
    }

    @Override
    public GetOutcome get(URI baseUrl, String credential, String id) {
        try {
            HttpRequest request = authed(baseUrl, "/api/artifacts/" + id, credential).GET().build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 200) {
                RemoteArtifact artifact = remoteArtifact(response.body());
                // a 200 that is not a usable artifact (a proxy / non-Cyntex reply) is treated as unreachable
                return artifact == null ? new GetOutcome.Unreachable() : new GetOutcome.Found(artifact);
            }
            if (status == 404) {
                return new GetOutcome.Absent();
            }
            Rejection r = rejection(response.body(), "The server refused the read.");
            return new GetOutcome.Rejected(r.code(), r.message());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GetOutcome.Unreachable();
        } catch (IOException | RuntimeException e) {
            return new GetOutcome.Unreachable();
        }
    }

    @Override
    public ListOutcome list(URI baseUrl, String credential, String kind) {
        try {
            String path = kind == null || kind.isBlank()
                    ? "/api/artifacts"
                    : "/api/artifacts?kind=" + URLEncoder.encode(kind, StandardCharsets.UTF_8);
            HttpRequest request = authed(baseUrl, path, credential).GET().build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                return new ListOutcome.Listed(remoteArtifacts(response.body()));
            }
            Rejection r = rejection(response.body(), "The server refused the read.");
            return new ListOutcome.Rejected(r.code(), r.message());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ListOutcome.Unreachable();
        } catch (IOException | RuntimeException e) {
            return new ListOutcome.Unreachable();
        }
    }

    @Override
    public LifecycleOutcome lifecycle(URI baseUrl, String credential, String pipelineId, String verb) {
        try {
            HttpRequest request = authed(baseUrl, "/api/pipelines/" + pipelineId + ":" + verb, credential)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                LifecycleOutcome.Accepted accepted = desiredState(response.body());
                // a 200 that is not a usable desired-state reply (a proxy / non-Cyntex answer) is unreachable
                return accepted == null ? new LifecycleOutcome.Unreachable() : accepted;
            }
            Rejection r = rejection(response.body(), "The server refused the lifecycle verb.");
            return new LifecycleOutcome.Rejected(r.code(), r.message());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LifecycleOutcome.Unreachable();
        } catch (IOException | RuntimeException e) {
            return new LifecycleOutcome.Unreachable();
        }
    }

    /** The new desired state decoded from a 200 body, or {@code null} unless it carries all three string fields. */
    private static LifecycleOutcome.Accepted desiredState(String body) {
        if (JsonReader.parse(body) instanceof Map<?, ?> m
                && m.get("pipelineId") instanceof String id
                && m.get("targetState") instanceof String state
                && m.get("revision") instanceof String revision) {
            return new LifecycleOutcome.Accepted(id, state, revision);
        }
        return null;
    }

    @Override
    public StatusOutcome status(URI baseUrl, String credential, String pipelineId) {
        try {
            HttpRequest request =
                    authed(baseUrl, "/api/pipelines/" + pipelineId + "/status", credential).GET().build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                StatusOutcome.Found found = statusFound(response.body());
                // a 200 that is not a usable status reply (a proxy / non-Cyntex answer) is unreachable
                return found == null ? new StatusOutcome.Unreachable() : found;
            }
            Rejection r = rejection(response.body(), "The server refused the read.");
            return new StatusOutcome.Rejected(r.code(), r.message());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StatusOutcome.Unreachable();
        } catch (IOException | RuntimeException e) {
            return new StatusOutcome.Unreachable();
        }
    }

    @Override
    public MetricsOutcome metrics(URI baseUrl, String credential, String pipelineId) {
        try {
            HttpRequest request =
                    authed(baseUrl, "/api/pipelines/" + pipelineId + "/metrics", credential).GET().build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                MetricsOutcome.Found found = metricsFound(response.body());
                return found == null ? new MetricsOutcome.Unreachable() : found;
            }
            Rejection r = rejection(response.body(), "The server refused the read.");
            return new MetricsOutcome.Rejected(r.code(), r.message());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MetricsOutcome.Unreachable();
        } catch (IOException | RuntimeException e) {
            return new MetricsOutcome.Unreachable();
        }
    }

    @Override
    public SnapshotOutcome snapshot(URI baseUrl, String credential, String pipelineId) {
        try {
            HttpRequest request =
                    authed(baseUrl, "/api/pipelines/" + pipelineId + "/snapshot", credential).GET().build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                SnapshotOutcome.Found found = snapshotFound(response.body());
                return found == null ? new SnapshotOutcome.Unreachable() : found;
            }
            Rejection r = rejection(response.body(), "The server refused the read.");
            return new SnapshotOutcome.Rejected(r.code(), r.message());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SnapshotOutcome.Unreachable();
        } catch (IOException | RuntimeException e) {
            return new SnapshotOutcome.Unreachable();
        }
    }

    /** The status decoded from a 200 body, or {@code null} unless it carries a string id and a string state. */
    private static StatusOutcome.Found statusFound(String body) {
        if (JsonReader.parse(body) instanceof Map<?, ?> m
                && m.get("pipelineId") instanceof String id
                && m.get("state") instanceof String state) {
            return new StatusOutcome.Found(id, state);
        }
        return null;
    }

    /**
     * The metrics decoded from a 200 body's {@code metrics} object, or {@code null} unless the body carries a
     * string id and a metrics object. Each numeric cell is read as a long; a non-numeric cell is dropped, so a
     * malformed entry never crashes the read. An empty object is a legitimate empty (no source wired yet).
     */
    private static MetricsOutcome.Found metricsFound(String body) {
        if (JsonReader.parse(body) instanceof Map<?, ?> m
                && m.get("pipelineId") instanceof String id
                && m.get("metrics") instanceof Map<?, ?> metrics) {
            Map<String, Long> stats = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : metrics.entrySet()) {
                if (e.getKey() instanceof String name && e.getValue() instanceof Number value) {
                    stats.put(name, value.longValue());
                }
            }
            return new MetricsOutcome.Found(id, stats);
        }
        return null;
    }

    /**
     * The per-table progress decoded from a 200 body's {@code snapshot} object, or {@code null} unless the body
     * carries a string id and a snapshot object. A table needs a numeric {@code rowsDone}; {@code rowsTotal} and
     * {@code donePct} are kept null when absent or null (unavailable), never faked. An empty object is a
     * legitimate empty (outside a snapshot phase).
     */
    private static SnapshotOutcome.Found snapshotFound(String body) {
        if (JsonReader.parse(body) instanceof Map<?, ?> m
                && m.get("pipelineId") instanceof String id
                && m.get("snapshot") instanceof Map<?, ?> snapshot) {
            Map<String, RemoteTableSnapshot> tables = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : snapshot.entrySet()) {
                if (e.getKey() instanceof String table && e.getValue() instanceof Map<?, ?> t
                        && t.get("rowsDone") instanceof Number rowsDone) {
                    Long rowsTotal = t.get("rowsTotal") instanceof Number n ? n.longValue() : null;
                    Integer donePct = t.get("donePct") instanceof Number n ? n.intValue() : null;
                    tables.put(table, new RemoteTableSnapshot(rowsDone.longValue(), rowsTotal, donePct));
                }
            }
            return new SnapshotOutcome.Found(id, tables);
        }
        return null;
    }

    @Override
    public LogsOutcome logs(URI baseUrl, String credential, String pipelineId) {
        try {
            HttpRequest request =
                    authed(baseUrl, "/api/pipelines/" + pipelineId + "/logs", credential).GET().build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                LogsOutcome.Found found = logsFound(response.body());
                return found == null ? new LogsOutcome.Unreachable() : found;
            }
            Rejection r = rejection(response.body(), "The server refused the read.");
            return new LogsOutcome.Rejected(r.code(), r.message());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LogsOutcome.Unreachable();
        } catch (IOException | RuntimeException e) {
            return new LogsOutcome.Unreachable();
        }
    }

    private static LogsOutcome.Found logsFound(String body) {
        if (JsonReader.parse(body) instanceof Map<?, ?> m
                && m.get("pipelineId") instanceof String id
                && m.get("lines") instanceof List<?> lines) {
            List<RemoteLogLine> parsed = new ArrayList<>();
            for (Object element : lines) {
                if (element instanceof Map<?, ?> line
                        && line.get("timestampMillis") instanceof Number ts
                        && line.get("level") instanceof String level
                        && line.get("message") instanceof String message) {
                    parsed.add(new RemoteLogLine(ts.longValue(), level, message));
                }
            }
            return new LogsOutcome.Found(id, parsed);
        }
        return null;
    }

    // --- streaming reads over a websocket (status --watch / logs --follow) -----------------------

    /** How long to wait after a live connection drops before re-attaching (same landing node in L1). */
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(1);

    /** How often the blocking stream loop wakes to check the stop signal while waiting. */
    private static final Duration STOP_POLL = Duration.ofMillis(200);

    @Override
    public void watchStatus(URI baseUrl, String credential, String pipelineId,
            StatusStream sink, BooleanSupplier stop) {
        stream(wsUri(baseUrl, "/api/pipelines/" + pipelineId + "/status/watch"), credential, stop, frame -> {
            StatusOutcome.Found found = statusFound(frame);
            if (found != null) {
                sink.state(found.pipelineId(), found.state());
            }
        });
    }

    @Override
    public void followLogs(URI baseUrl, String credential, String pipelineId,
            LogStream sink, BooleanSupplier stop) {
        stream(wsUri(baseUrl, "/api/pipelines/" + pipelineId + "/logs/follow"), credential, stop, frame -> {
            LogsOutcome.Found found = logsFound(frame);
            if (found != null && !found.lines().isEmpty()) {
                sink.lines(found.pipelineId(), found.lines());
            }
        });
    }

    /**
     * Opens a websocket to {@code wsUri}, delivering each decoded text frame to {@code onFrame}, and blocks
     * until {@code stop} signals. A refused or unreachable handshake ends the stream; a live connection that
     * later drops is re-attached after a short backoff until stopped. Never throws.
     */
    private void stream(URI wsUri, String credential, BooleanSupplier stop, Consumer<String> onFrame) {
        while (!stop.getAsBoolean()) {
            CountDownLatch closed = new CountDownLatch(1);
            WebSocket ws;
            try {
                ws = client().newWebSocketBuilder()
                        .header("Authorization", "Bearer " + credential)
                        .buildAsync(wsUri, new StreamListener(onFrame, closed))
                        .join();
            } catch (RuntimeException handshakeFailed) {
                // join() wraps a refused (401/403) or unreachable handshake in a CompletionException (a
                // RuntimeException); either way it cannot be streamed, so end the stream.
                return;
            }
            awaitClosedOrStop(closed, stop);
            ws.abort();
            if (stop.getAsBoolean()) {
                return;
            }
            // A live connection dropped (not a stop): re-attach after a short backoff.
            if (!sleepUnlessStopped(RECONNECT_BACKOFF, stop)) {
                return;
            }
        }
    }

    /** The {@code ws(s)} endpoint for {@code path} against an {@code http(s)} base, tolerating a trailing slash. */
    static URI wsUri(URI baseUrl, String path) {
        String base = baseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String wsBase;
        if (base.startsWith("https://")) {
            wsBase = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            wsBase = "ws://" + base.substring("http://".length());
        } else {
            wsBase = base;   // already a ws / wss base
        }
        return URI.create(wsBase + path);
    }

    /** Waits until the connection closes or {@code stop} is signalled, whichever comes first. */
    private static void awaitClosedOrStop(CountDownLatch closed, BooleanSupplier stop) {
        try {
            while (!stop.getAsBoolean()) {
                if (closed.await(STOP_POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sleeps {@code duration} in short chunks; returns {@code false} the moment {@code stop} is signalled. */
    private static boolean sleepUnlessStopped(Duration duration, BooleanSupplier stop) {
        long remaining = duration.toMillis();
        try {
            while (remaining > 0) {
                if (stop.getAsBoolean()) {
                    return false;
                }
                long chunk = Math.min(remaining, STOP_POLL.toMillis());
                Thread.sleep(chunk);
                remaining -= chunk;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !stop.getAsBoolean();
    }

    /** A websocket listener that reassembles text frames and hands each complete one to a decoder. */
    private static final class StreamListener implements WebSocket.Listener {
        private final Consumer<String> onFrame;
        private final CountDownLatch closed;
        private final StringBuilder partial = new StringBuilder();

        StreamListener(Consumer<String> onFrame, CountDownLatch closed) {
            this.onFrame = onFrame;
            this.closed = closed;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String frame = partial.toString();
                partial.setLength(0);
                try {
                    onFrame.accept(frame);
                } catch (RuntimeException malformed) {
                    // A malformed frame is dropped, never crashes the stream.
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.countDown();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.countDown();
            return null;
        }
    }

    /** A request builder for {@code path} against a base, carrying the timeout and the bearer credential. */
    private static HttpRequest.Builder authed(URI baseUrl, String path, String credential) {
        return HttpRequest.newBuilder(endpoint(baseUrl, path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + credential);
    }

    /** The absolute request URI for {@code path} against a base, tolerating a trailing slash on the base. */
    private static URI endpoint(URI baseUrl, String path) {
        String base = baseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    /** The apply request body: {@code {"drafts":[{"source":..,"content":..}]}} in submission order. */
    private static String applyBody(List<LocalDraft> drafts) {
        List<Object> array = new ArrayList<>();
        for (LocalDraft draft : drafts) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("source", draft.source());
            d.put("content", draft.content());
            array.add(d);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drafts", array);
        return JsonOut.write(body);
    }

    /** The apply outcomes decoded from a 200 body's {@code outcomes} array; empty if the shape is unexpected. */
    private static List<ApplyOutcome.Item> applyItems(String body) {
        List<ApplyOutcome.Item> items = new ArrayList<>();
        if (JsonReader.parse(body) instanceof Map<?, ?> map && map.get("outcomes") instanceof List<?> outcomes) {
            for (Object o : outcomes) {
                if (o instanceof Map<?, ?> m
                        && m.get("id") instanceof String id
                        && m.get("kind") instanceof String kind
                        && m.get("change") instanceof String change) {
                    items.add(new ApplyOutcome.Item(id, kind, change));
                }
            }
        }
        return items;
    }

    /** One stored artifact decoded from a 200 body, or {@code null} if the body is not a usable artifact. */
    private static RemoteArtifact remoteArtifact(String body) {
        if (JsonReader.parse(body) instanceof Map<?, ?> m) {
            return artifactOf(m);
        }
        return null;
    }

    /** The stored artifacts decoded from a 200 body's {@code artifacts} array; empty if the shape is unexpected. */
    private static List<RemoteArtifact> remoteArtifacts(String body) {
        List<RemoteArtifact> artifacts = new ArrayList<>();
        if (JsonReader.parse(body) instanceof Map<?, ?> map && map.get("artifacts") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    RemoteArtifact artifact = artifactOf(m);
                    if (artifact != null) {
                        artifacts.add(artifact);
                    }
                }
            }
        }
        return artifacts;
    }

    /** One artifact from a decoded JSON object, or {@code null} unless it carries all three string fields. */
    private static RemoteArtifact artifactOf(Map<?, ?> m) {
        if (m.get("id") instanceof String id
                && m.get("kind") instanceof String kind
                && m.get("canonicalForm") instanceof String canonical) {
            return new RemoteArtifact(id, kind, canonical);
        }
        return null;
    }

    /** The string value of {@code key} in a JSON object body, or {@code null} if absent / not a string. */
    private static String stringField(String body, String key) {
        try {
            if (JsonReader.parse(body) instanceof Map<?, ?> map && map.get(key) instanceof String s) {
                return s;
            }
        } catch (RuntimeException malformed) {
            // a malformed body has no usable field
        }
        return null;
    }

    /** A parsed coded refusal: the server's code and message, or a fixed generic message if not coded. */
    private record Rejection(String code, String message) {
    }

    /**
     * Turns a non-2xx response body into a coded rejection ({@code {code, message}}), or a generic one
     * carrying {@code genericMessage} when the body is not coded / not parseable — a non-coded error body
     * (e.g. a container 500 page) is still a refusal, never a crash and never leaked raw to the user.
     */
    private static Rejection rejection(String body, String genericMessage) {
        try {
            if (JsonReader.parse(body) instanceof Map<?, ?> map && map.get("code") instanceof String code) {
                String message = map.get("message") instanceof String m ? m : code;
                return new Rejection(code, message);
            }
        } catch (RuntimeException malformed) {
            // fall through: a non-coded / unparseable error body is still a refusal, not a crash
        }
        return new Rejection("", genericMessage);
    }
}
