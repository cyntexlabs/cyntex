package io.cyntex.cli;

import com.sun.net.httpserver.HttpServer;
import io.cyntex.core.common.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production HTTP reachability probe against a tiny in-JVM server: {@code GET /healthz} returning
 * 200 means healthy, any non-200 or any I/O failure (connection refused) means not healthy and never
 * throws. Uses the JDK's own {@code HttpServer} so the test carries no dependency.
 */
class HttpControlPlaneClientTest {

    private static HttpServer serverReplying(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthz", exchange -> {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static URI baseOf(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void healthyWhenHealthzReturns200() throws Exception {
        HttpServer server = serverReplying(200, "ok");
        try {
            assertThat(new HttpControlPlaneClient().isHealthy(baseOf(server))).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void notHealthyWhenHealthzReturnsNon200() throws Exception {
        HttpServer server = serverReplying(503, null);
        try {
            assertThat(new HttpControlPlaneClient().isHealthy(baseOf(server))).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void notHealthyForAnUnreachablePortWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }   // the port is closed on scope exit -> a connect there is refused
        URI base = URI.create("http://127.0.0.1:" + closedPort);
        assertThat(new HttpControlPlaneClient().isHealthy(base)).isFalse();
    }

    @Test
    void notHealthyForAHostlessUriWithoutThrowing() {
        // `http://foo:bar` parses but a non-numeric port makes the authority registry-based, so it has
        // no host; building the request throws IllegalArgumentException, which must resolve to not
        // healthy rather than propagate, honoring the never-throws contract
        assertThat(new HttpControlPlaneClient().isHealthy(URI.create("http://foo:bar"))).isFalse();
    }

    // --- login: POST /auth/login -----------------------------------------------------------------

    /** A server whose {@code /auth/login} records the request body and replies with a fixed status + body. */
    private static HttpServer loginServer(int status, String responseBody, AtomicReference<String> captured)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/login", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void loginReturnsSuccessWithTheTokenOn200() throws Exception {
        HttpServer server = loginServer(200, "{\"token\":\"jwt-xyz\"}", new AtomicReference<>());
        try {
            LoginOutcome outcome = new HttpControlPlaneClient().login(baseOf(server), "alice", "s3cret");
            assertThat(outcome).isEqualTo(new LoginOutcome.Success("jwt-xyz"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loginSendsTheUsernameAndPasswordAsAProperlyEscapedJsonBody() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = loginServer(200, "{\"token\":\"t\"}", body);
        try {
            // a password with a quote must survive JSON escaping and round-trip back on the server side
            new HttpControlPlaneClient().login(baseOf(server), "alice", "p@ss\"word");
            Map<?, ?> sent = (Map<?, ?>) JsonReader.parse(body.get());
            assertThat(sent.get("username")).isEqualTo("alice");
            assertThat(sent.get("password")).isEqualTo("p@ss\"word");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loginReturnsRejectedWithTheServerCodeAndMessageOn401() throws Exception {
        String errorBody = "{\"code\":\"control.auth-failed\",\"params\":{},\"message\":\"Login failed.\"}";
        HttpServer server = loginServer(401, errorBody, new AtomicReference<>());
        try {
            LoginOutcome outcome = new HttpControlPlaneClient().login(baseOf(server), "alice", "wrong");
            assertThat(outcome).isEqualTo(new LoginOutcome.Rejected("control.auth-failed", "Login failed."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loginTreatsANonCodedErrorBodyAsAGenericRejectionRevealingNothing() throws Exception {
        // a non-JSON error body (e.g. a container 500 page) must not crash login, and the raw body must
        // not leak to the user: it is refused with a fixed generic message, no code
        HttpServer server = loginServer(500, "<html>Internal Server Error</html>", new AtomicReference<>());
        try {
            LoginOutcome outcome = new HttpControlPlaneClient().login(baseOf(server), "a", "b");
            assertThat(outcome).isEqualTo(new LoginOutcome.Rejected("", "Login was refused by the server."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loginTreatsA200WithoutAUsableTokenAsUnreachableNotASuccess() throws Exception {
        // a bodyless / tokenless 200 (a reverse proxy, captive portal, or non-Cyntex server) is not a
        // real login and must never authenticate the session
        HttpServer emptyObject = loginServer(200, "{}", new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().login(baseOf(emptyObject), "a", "b"))
                    .isInstanceOf(LoginOutcome.Unreachable.class);
        } finally {
            emptyObject.stop(0);
        }
        HttpServer blankToken = loginServer(200, "{\"token\":\"\"}", new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().login(baseOf(blankToken), "a", "b"))
                    .isInstanceOf(LoginOutcome.Unreachable.class);
        } finally {
            blankToken.stop(0);
        }
    }

    @Test
    void loginReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        LoginOutcome outcome =
                new HttpControlPlaneClient().login(URI.create("http://127.0.0.1:" + closedPort), "a", "b");
        assertThat(outcome).isInstanceOf(LoginOutcome.Unreachable.class);
    }

    // --- online verbs: apply / get / list under /api, authenticated by a bearer credential ---------

    /** What the fake server saw for one request: method, path, query, the Authorization header, and body. */
    private record CapturedRequest(String method, String path, String query, String authorization, String body) {
    }

    /**
     * A server that answers one {@code context} with a fixed status + body and records the request it saw,
     * so a test can assert the client sent the right method, path, bearer credential and JSON body.
     */
    private static HttpServer apiServer(String context, int status, String responseBody,
            AtomicReference<CapturedRequest> captured) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(context, exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    body));
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void applyPostsTheDraftsWithABearerCredentialAndReturnsTheOutcomes() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/artifacts:apply", 200,
                "{\"outcomes\":[{\"id\":\"src_kfk\",\"kind\":\"source\",\"change\":\"CREATED\",\"contentHash\":\"h1\"},"
                        + "{\"id\":\"kfk2my\",\"kind\":\"pipeline\",\"change\":\"UNCHANGED\",\"contentHash\":\"h2\"}]}",
                seen);
        try {
            ApplyOutcome outcome = new HttpControlPlaneClient().apply(baseOf(server), "tok-abc",
                    List.of(new LocalDraft("src_kfk.cyn.yml", "kind: source\nid: src_kfk\n")));
            assertThat(outcome).isInstanceOf(ApplyOutcome.Applied.class);
            ApplyOutcome.Applied applied = (ApplyOutcome.Applied) outcome;
            assertThat(applied.items()).containsExactly(
                    new ApplyOutcome.Item("src_kfk", "source", "CREATED"),
                    new ApplyOutcome.Item("kfk2my", "pipeline", "UNCHANGED"));
            assertThat(seen.get().method()).isEqualTo("POST");
            assertThat(seen.get().path()).isEqualTo("/api/artifacts:apply");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-abc");
            Map<?, ?> sent = (Map<?, ?>) JsonReader.parse(seen.get().body());
            assertThat(sent.get("drafts")).isInstanceOf(List.class);
            assertThat(seen.get().body()).contains("src_kfk.cyn.yml").contains("kind: source");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void applyReturnsRejectedWithTheServerCodeAndMessageOnACodedError() throws Exception {
        HttpServer server = apiServer("/api/artifacts:apply", 400,
                "{\"code\":\"dsl.illegal-value\",\"params\":{},\"message\":\"Not a known kind.\"}",
                new AtomicReference<>());
        try {
            ApplyOutcome outcome = new HttpControlPlaneClient()
                    .apply(baseOf(server), "tok", List.of(new LocalDraft("bad.cyn.yml", "kind: nope\n")));
            assertThat(outcome).isEqualTo(new ApplyOutcome.Rejected("dsl.illegal-value", "Not a known kind."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void applyReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ApplyOutcome outcome = new HttpControlPlaneClient().apply(URI.create("http://127.0.0.1:" + closedPort),
                "tok", List.of(new LocalDraft("a.cyn.yml", "kind: source\n")));
        assertThat(outcome).isInstanceOf(ApplyOutcome.Unreachable.class);
    }

    // --- lifecycle: POST /api/pipelines/{id}:{verb} under /api, authenticated ----------------------

    @Test
    void lifecyclePostsToTheColonMethodPathWithTheBearerAndReturnsTheNewState() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/pipelines/pl1:start", 200,
                "{\"pipelineId\":\"pl1\",\"targetState\":\"RUNNING\",\"revision\":\"rev-abc\"}", seen);
        try {
            LifecycleOutcome outcome =
                    new HttpControlPlaneClient().lifecycle(baseOf(server), "tok-abc", "pl1", "start");
            assertThat(outcome).isEqualTo(new LifecycleOutcome.Accepted("pl1", "RUNNING", "rev-abc"));
            assertThat(seen.get().method()).isEqualTo("POST");
            assertThat(seen.get().path()).isEqualTo("/api/pipelines/pl1:start");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-abc");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lifecycleReturnsRejectedWithTheServerCodeAndMessageOnAConflict() throws Exception {
        HttpServer server = apiServer("/api/pipelines/pl1:pause", 409,
                "{\"code\":\"lifecycle.illegal-transition\",\"params\":{},\"message\":\"Not running.\"}",
                new AtomicReference<>());
        try {
            LifecycleOutcome outcome =
                    new HttpControlPlaneClient().lifecycle(baseOf(server), "tok", "pl1", "pause");
            assertThat(outcome).isEqualTo(
                    new LifecycleOutcome.Rejected("lifecycle.illegal-transition", "Not running."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lifecycleReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        LifecycleOutcome outcome = new HttpControlPlaneClient()
                .lifecycle(URI.create("http://127.0.0.1:" + closedPort), "tok", "pl1", "start");
        assertThat(outcome).isInstanceOf(LifecycleOutcome.Unreachable.class);
    }

    @Test
    void getReturnsFoundWithTheStoredArtifactAndSendsTheCredential() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/artifacts/", 200,
                "{\"id\":\"src_kfk\",\"kind\":\"source\",\"canonicalForm\":\"kind: source\\nid: src_kfk\\n\"}", seen);
        try {
            GetOutcome outcome = new HttpControlPlaneClient().get(baseOf(server), "tok-xyz", "src_kfk");
            assertThat(outcome).isEqualTo(new GetOutcome.Found(
                    new RemoteArtifact("src_kfk", "source", "kind: source\nid: src_kfk\n")));
            assertThat(seen.get().method()).isEqualTo("GET");
            assertThat(seen.get().path()).isEqualTo("/api/artifacts/src_kfk");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-xyz");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getReturnsAbsentOnA404() throws Exception {
        HttpServer server = apiServer("/api/artifacts/", 404, null, new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().get(baseOf(server), "tok", "missing"))
                    .isInstanceOf(GetOutcome.Absent.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getReturnsRejectedWithTheServerCodeAndMessageOnACodedErrorStatus() throws Exception {
        // a non-404 error status (here 403 control.forbidden) is a coded refusal, distinct from Absent
        HttpServer server = apiServer("/api/artifacts/", 403,
                "{\"code\":\"control.forbidden\",\"params\":{},\"message\":\"You lack the grade.\"}",
                new AtomicReference<>());
        try {
            GetOutcome outcome = new HttpControlPlaneClient().get(baseOf(server), "tok", "src_kfk");
            assertThat(outcome).isEqualTo(new GetOutcome.Rejected("control.forbidden", "You lack the grade."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(new HttpControlPlaneClient().get(URI.create("http://127.0.0.1:" + closedPort), "tok", "x"))
                .isInstanceOf(GetOutcome.Unreachable.class);
    }

    @Test
    void listReturnsTheArtifactsAndSendsTheKindFilterAndCredential() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/artifacts", 200,
                "{\"artifacts\":[{\"id\":\"src_kfk\",\"kind\":\"source\",\"canonicalForm\":\"kind: source\\n\"}]}", seen);
        try {
            ListOutcome outcome = new HttpControlPlaneClient().list(baseOf(server), "tok-1", "source");
            assertThat(outcome).isInstanceOf(ListOutcome.Listed.class);
            assertThat(((ListOutcome.Listed) outcome).artifacts())
                    .containsExactly(new RemoteArtifact("src_kfk", "source", "kind: source\n"));
            assertThat(seen.get().path()).isEqualTo("/api/artifacts");
            assertThat(seen.get().query()).isEqualTo("kind=source");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listWithoutAKindSendsNoQueryFilter() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/artifacts", 200, "{\"artifacts\":[]}", seen);
        try {
            ListOutcome outcome = new HttpControlPlaneClient().list(baseOf(server), "tok", null);
            assertThat(outcome).isInstanceOf(ListOutcome.Listed.class);
            assertThat(((ListOutcome.Listed) outcome).artifacts()).isEmpty();
            assertThat(seen.get().query()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listReturnsRejectedWithTheServerCodeAndMessageOnACodedErrorStatus() throws Exception {
        HttpServer server = apiServer("/api/artifacts", 403,
                "{\"code\":\"control.forbidden\",\"params\":{},\"message\":\"You lack the grade.\"}",
                new AtomicReference<>());
        try {
            ListOutcome outcome = new HttpControlPlaneClient().list(baseOf(server), "tok", null);
            assertThat(outcome).isEqualTo(new ListOutcome.Rejected("control.forbidden", "You lack the grade."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(new HttpControlPlaneClient().list(URI.create("http://127.0.0.1:" + closedPort), "tok", null))
                .isInstanceOf(ListOutcome.Unreachable.class);
    }

    // --- observation reads: GET /api/pipelines/{id}/{face} under /api, authenticated ---------------

    @Test
    void statusGetsTheStatusFaceWithTheBearerAndReturnsTheState() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/pipelines/pl1/status", 200,
                "{\"pipelineId\":\"pl1\",\"state\":\"RUNNING\"}", seen);
        try {
            StatusOutcome outcome = new HttpControlPlaneClient().status(baseOf(server), "tok-abc", "pl1");
            assertThat(outcome).isEqualTo(new StatusOutcome.Found("pl1", "RUNNING"));
            assertThat(seen.get().method()).isEqualTo("GET");
            assertThat(seen.get().path()).isEqualTo("/api/pipelines/pl1/status");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-abc");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void statusReturnsRejectedWithTheServerCodeAndMessageOnACodedError() throws Exception {
        HttpServer server = apiServer("/api/pipelines/ghost/status", 404,
                "{\"code\":\"monitor.no-observation\",\"params\":{\"pipeline\":\"ghost\"},"
                        + "\"message\":\"No observation is available for pipeline ghost.\"}",
                new AtomicReference<>());
        try {
            StatusOutcome outcome = new HttpControlPlaneClient().status(baseOf(server), "tok", "ghost");
            assertThat(outcome).isEqualTo(new StatusOutcome.Rejected(
                    "monitor.no-observation", "No observation is available for pipeline ghost."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void statusReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(new HttpControlPlaneClient().status(URI.create("http://127.0.0.1:" + closedPort), "tok", "pl1"))
                .isInstanceOf(StatusOutcome.Unreachable.class);
    }

    @Test
    void metricsGetsTheMetricsFaceAndReturnsTheOpenMap() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/pipelines/pl1/metrics", 200,
                "{\"pipelineId\":\"pl1\",\"metrics\":{\"recordCount\":42,\"errorCount\":0}}", seen);
        try {
            MetricsOutcome outcome = new HttpControlPlaneClient().metrics(baseOf(server), "tok-abc", "pl1");
            assertThat(outcome)
                    .isEqualTo(new MetricsOutcome.Found("pl1", Map.of("recordCount", 42L, "errorCount", 0L)));
            assertThat(seen.get().method()).isEqualTo("GET");
            assertThat(seen.get().path()).isEqualTo("/api/pipelines/pl1/metrics");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-abc");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void metricsReturnsAnEmptyMapWhenNoMetricSourceIsWiredYet() throws Exception {
        // Honest-empty: no metric source is wired, so the open map is empty — never faked.
        HttpServer server = apiServer("/api/pipelines/pl1/metrics", 200,
                "{\"pipelineId\":\"pl1\",\"metrics\":{}}", new AtomicReference<>());
        try {
            MetricsOutcome outcome = new HttpControlPlaneClient().metrics(baseOf(server), "tok", "pl1");
            assertThat(outcome).isEqualTo(new MetricsOutcome.Found("pl1", Map.of()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void metricsReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(new HttpControlPlaneClient().metrics(URI.create("http://127.0.0.1:" + closedPort), "tok", "pl1"))
                .isInstanceOf(MetricsOutcome.Unreachable.class);
    }

    @Test
    void snapshotGetsThePerTableProgressIncludingAnUnavailableTotal() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/pipelines/pl1/snapshot", 200,
                "{\"pipelineId\":\"pl1\",\"snapshot\":{"
                        + "\"orders\":{\"rowsDone\":10,\"rowsTotal\":100,\"donePct\":10},"
                        + "\"events\":{\"rowsDone\":5,\"rowsTotal\":null,\"donePct\":null}}}", seen);
        try {
            SnapshotOutcome outcome = new HttpControlPlaneClient().snapshot(baseOf(server), "tok", "pl1");
            assertThat(outcome).isEqualTo(new SnapshotOutcome.Found("pl1", Map.of(
                    "orders", new RemoteTableSnapshot(10, 100L, 10),
                    "events", new RemoteTableSnapshot(5, null, null))));
            assertThat(seen.get().method()).isEqualTo("GET");
            assertThat(seen.get().path()).isEqualTo("/api/pipelines/pl1/snapshot");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void snapshotReturnsRejectedWithTheServerCodeAndMessageOnACodedError() throws Exception {
        HttpServer server = apiServer("/api/pipelines/pl1/snapshot", 403,
                "{\"code\":\"control.forbidden\",\"params\":{},\"message\":\"You lack the grade.\"}",
                new AtomicReference<>());
        try {
            SnapshotOutcome outcome = new HttpControlPlaneClient().snapshot(baseOf(server), "tok", "pl1");
            assertThat(outcome).isEqualTo(new SnapshotOutcome.Rejected("control.forbidden", "You lack the grade."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void snapshotReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(new HttpControlPlaneClient().snapshot(URI.create("http://127.0.0.1:" + closedPort), "tok", "pl1"))
                .isInstanceOf(SnapshotOutcome.Unreachable.class);
    }

    @Test
    void logsGetsThePipelineTailOldestToNewest() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/pipelines/pl1/logs", 200,
                "{\"pipelineId\":\"pl1\",\"lines\":["
                        + "{\"timestampMillis\":1700000000000,\"level\":\"INFO\",\"message\":\"submitted job\"},"
                        + "{\"timestampMillis\":1700000000100,\"level\":\"WARN\",\"message\":\"slow tick\"}]}", seen);
        try {
            LogsOutcome outcome = new HttpControlPlaneClient().logs(baseOf(server), "tok", "pl1");
            assertThat(outcome).isEqualTo(new LogsOutcome.Found("pl1", List.of(
                    new RemoteLogLine(1700000000000L, "INFO", "submitted job"),
                    new RemoteLogLine(1700000000100L, "WARN", "slow tick"))));
            assertThat(seen.get().method()).isEqualTo("GET");
            assertThat(seen.get().path()).isEqualTo("/api/pipelines/pl1/logs");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void logsWithNoLinesIsABenignEmptyFound() throws Exception {
        HttpServer server = apiServer("/api/pipelines/pl1/logs", 200,
                "{\"pipelineId\":\"pl1\",\"lines\":[]}", new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().logs(baseOf(server), "tok", "pl1"))
                    .isEqualTo(new LogsOutcome.Found("pl1", List.of()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void logsDropsAMalformedLineButKeepsTheValidOnes() throws Exception {
        // A malformed line entry (missing fields) is dropped rather than crashing the read; valid lines in the
        // same tail are still returned.
        HttpServer server = apiServer("/api/pipelines/pl1/logs", 200,
                "{\"pipelineId\":\"pl1\",\"lines\":["
                        + "{\"timestampMillis\":1,\"level\":\"INFO\",\"message\":\"kept\"},"
                        + "{\"level\":\"WARN\"}]}", new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().logs(baseOf(server), "tok", "pl1"))
                    .isEqualTo(new LogsOutcome.Found("pl1", List.of(new RemoteLogLine(1L, "INFO", "kept"))));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void logsReturnsRejectedWithTheServerCodeAndMessageOnACodedError() throws Exception {
        HttpServer server = apiServer("/api/pipelines/pl1/logs", 403,
                "{\"code\":\"control.forbidden\",\"params\":{},\"message\":\"You lack the grade.\"}",
                new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().logs(baseOf(server), "tok", "pl1"))
                    .isEqualTo(new LogsOutcome.Rejected("control.forbidden", "You lack the grade."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void logsReturnsUnreachableOnAWrongShapeTwoHundred() throws Exception {
        // A 200 whose body is not the expected shape (a proxy splash, a wrong endpoint) is unusable: it maps
        // to unreachable, not a falsely-successful empty tail.
        HttpServer server = apiServer("/api/pipelines/pl1/logs", 200, "{\"unexpected\":true}", new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().logs(baseOf(server), "tok", "pl1"))
                    .isInstanceOf(LogsOutcome.Unreachable.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void logsReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(new HttpControlPlaneClient().logs(URI.create("http://127.0.0.1:" + closedPort), "tok", "pl1"))
                .isInstanceOf(LogsOutcome.Unreachable.class);
    }

    @Test
    void snapshotReturnsAnEmptyMapOutsideASnapshotPhase() throws Exception {
        // Honest-empty: outside a snapshot phase the per-table map is empty — a legitimate Found, not a miss.
        HttpServer server = apiServer("/api/pipelines/pl1/snapshot", 200,
                "{\"pipelineId\":\"pl1\",\"snapshot\":{}}", new AtomicReference<>());
        try {
            SnapshotOutcome outcome = new HttpControlPlaneClient().snapshot(baseOf(server), "tok", "pl1");
            assertThat(outcome).isEqualTo(new SnapshotOutcome.Found("pl1", Map.of()));
        } finally {
            server.stop(0);
        }
    }

    // --- a well-formed 200 that is not a usable read reply resolves to unreachable (never a fabricated Found) ---

    @Test
    void statusTreatsAShapeWrong200AsUnreachableNotAFabricatedState() throws Exception {
        // A 200 whose body is valid JSON but not a usable status reply (a reverse proxy / non-Cyntex answer)
        // must never fabricate a state — it resolves to unreachable, upholding the never-throw seam.
        HttpServer server = apiServer("/api/pipelines/pl1/status", 200, "{\"foo\":\"bar\"}", new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().status(baseOf(server), "tok", "pl1"))
                    .isInstanceOf(StatusOutcome.Unreachable.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void metricsTreatsAShapeWrong200AsUnreachableNotAFabricatedMap() throws Exception {
        // A 200 with a non-object metrics field is not a usable metrics reply; it must not be read as an
        // empty (honest-empty is a real object), so it resolves to unreachable rather than a faked empty map.
        HttpServer server = apiServer("/api/pipelines/pl1/metrics", 200,
                "{\"pipelineId\":\"pl1\",\"metrics\":\"nope\"}", new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().metrics(baseOf(server), "tok", "pl1"))
                    .isInstanceOf(MetricsOutcome.Unreachable.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void snapshotTreatsAShapeWrong200AsUnreachableNotAFabricatedMap() throws Exception {
        HttpServer server = apiServer("/api/pipelines/pl1/snapshot", 200, "{\"pipelineId\":\"pl1\"}",
                new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().snapshot(baseOf(server), "tok", "pl1"))
                    .isInstanceOf(SnapshotOutcome.Unreachable.class);
        } finally {
            server.stop(0);
        }
    }
}
