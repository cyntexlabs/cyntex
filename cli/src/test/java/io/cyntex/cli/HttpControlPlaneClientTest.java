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
    void registerPostsTheBase64ArtifactWithABearerCredentialAndReturnsTheRegistration() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/connectors:register", 200,
                "{\"connectorId\":\"orders\",\"contentHash\":\"hash-abc\",\"pdkApiVersion\":\"1.3.5\","
                        + "\"newlyRegistered\":true}",
                seen);
        try {
            ConnectorRegisterOutcome outcome =
                    new HttpControlPlaneClient().register(baseOf(server), "tok-abc", new byte[] {1, 2, 3, 4});
            assertThat(outcome).isEqualTo(new ConnectorRegisterOutcome.Registered(
                    new RegisteredConnector("orders", "hash-abc", "1.3.5", true)));
            assertThat(seen.get().method()).isEqualTo("POST");
            assertThat(seen.get().path()).isEqualTo("/api/connectors:register");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-abc");
            // The artifact bytes travel base64-encoded in the JSON body.
            Map<?, ?> sent = (Map<?, ?>) JsonReader.parse(seen.get().body());
            assertThat(sent.get("artifact")).isEqualTo("AQIDBA==");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void registerReturnsRejectedWithTheServerCodeAndMessageOnACodedError() throws Exception {
        HttpServer server = apiServer("/api/connectors:register", 400,
                "{\"code\":\"connector.registration-conflict\",\"params\":{},"
                        + "\"message\":\"A different artifact already holds that id.\"}",
                new AtomicReference<>());
        try {
            ConnectorRegisterOutcome outcome =
                    new HttpControlPlaneClient().register(baseOf(server), "tok", new byte[] {9});
            assertThat(outcome).isEqualTo(new ConnectorRegisterOutcome.Rejected(
                    "connector.registration-conflict", "A different artifact already holds that id."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void registerReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ConnectorRegisterOutcome outcome = new HttpControlPlaneClient()
                .register(URI.create("http://127.0.0.1:" + closedPort), "tok", new byte[] {1});
        assertThat(outcome).isInstanceOf(ConnectorRegisterOutcome.Unreachable.class);
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

    // --- connection test: POST /api/connections:test, authenticated, decodes the structured report -----

    @Test
    void testPostsTheConnectionAndDecodesTheStructuredReport() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/connections:test", 200,
                "{\"connectionId\":\"conn_ora\",\"connectorId\":\"oracle\",\"outcome\":\"PASSED\",\"checks\":["
                        + "{\"name\":\"ping\",\"status\":\"PASSED\",\"message\":null,\"reason\":null,"
                        + "\"solution\":null,\"connectorErrorCode\":null},"
                        + "{\"name\":\"version\",\"status\":\"WARNING\",\"message\":\"server is old\",\"reason\":null,"
                        + "\"solution\":null,\"connectorErrorCode\":null}],\"testedAt\":1752000000000}",
                seen);
        try {
            ConnectionTestOutcome outcome = new HttpControlPlaneClient()
                    .test(baseOf(server), "tok-abc", "conn_ora", "oracle", Map.of("host", "10.20.0.15"));

            assertThat(outcome).isInstanceOf(ConnectionTestOutcome.Tested.class);
            ConnectionReport report = ((ConnectionTestOutcome.Tested) outcome).report();
            assertThat(report.connectionId()).isEqualTo("conn_ora");
            assertThat(report.connectorId()).isEqualTo("oracle");
            assertThat(report.outcome()).isEqualTo("PASSED");
            assertThat(report.testedAt()).isEqualTo(1752000000000L);
            assertThat(report.checks()).containsExactly(
                    new ConnectionReport.Check("ping", "PASSED", null, null, null, null),
                    new ConnectionReport.Check("version", "WARNING", "server is old", null, null, null));

            // the request carried the connection id, its connector and settings under a bearer credential
            assertThat(seen.get().method()).isEqualTo("POST");
            assertThat(seen.get().path()).isEqualTo("/api/connections:test");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-abc");
            Map<?, ?> sent = (Map<?, ?>) JsonReader.parse(seen.get().body());
            assertThat(sent.get("id")).isEqualTo("conn_ora");
            assertThat(sent.get("connectorId")).isEqualTo("oracle");
            assertThat(sent.get("settings")).isEqualTo(Map.of("host", "10.20.0.15"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testReturnsRejectedWithTheServerCodeAndMessageOnACodedErrorStatus() throws Exception {
        HttpServer server = apiServer("/api/connections:test", 400,
                "{\"code\":\"control.malformed-request\",\"params\":{},\"message\":\"a connectorId is required\"}",
                new AtomicReference<>());
        try {
            ConnectionTestOutcome outcome = new HttpControlPlaneClient()
                    .test(baseOf(server), "tok", "conn_ora", "oracle", Map.of());
            assertThat(outcome).isEqualTo(
                    new ConnectionTestOutcome.Rejected("control.malformed-request", "a connectorId is required"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(new HttpControlPlaneClient()
                .test(URI.create("http://127.0.0.1:" + closedPort), "tok", "c", "oracle", Map.of()))
                .isInstanceOf(ConnectionTestOutcome.Unreachable.class);
    }

    // --- connection test result: GET /api/connections/{id}/test-result, decodes the stored report ---------

    @Test
    void testResultReturnsFoundWithTheStoredReportAndSendsTheCredential() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/connections/", 200,
                "{\"connectionId\":\"conn_ora\",\"connectorId\":\"oracle\",\"outcome\":\"FAILED\",\"checks\":["
                        + "{\"name\":\"Login\",\"status\":\"FAILED\",\"message\":\"auth failed\",\"reason\":null,"
                        + "\"solution\":null,\"connectorErrorCode\":\"11000\"}],\"testedAt\":1752000000000}",
                seen);
        try {
            ConnectionTestResultOutcome outcome =
                    new HttpControlPlaneClient().testResult(baseOf(server), "tok-xyz", "conn_ora");

            assertThat(outcome).isInstanceOf(ConnectionTestResultOutcome.Found.class);
            ConnectionReport report = ((ConnectionTestResultOutcome.Found) outcome).report();
            assertThat(report.connectionId()).isEqualTo("conn_ora");
            assertThat(report.connectorId()).isEqualTo("oracle");
            assertThat(report.outcome()).isEqualTo("FAILED");
            assertThat(report.testedAt()).isEqualTo(1752000000000L);
            assertThat(report.checks()).containsExactly(
                    new ConnectionReport.Check("Login", "FAILED", "auth failed", null, null, "11000"));

            // the read is a GET to the connection's result path, under a bearer credential
            assertThat(seen.get().method()).isEqualTo("GET");
            assertThat(seen.get().path()).isEqualTo("/api/connections/conn_ora/test-result");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-xyz");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testResultReturnsAbsentOnA404() throws Exception {
        // a 404 is "never tested", distinct from a coded refusal
        HttpServer server = apiServer("/api/connections/", 404, null, new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().testResult(baseOf(server), "tok", "never_tested"))
                    .isInstanceOf(ConnectionTestResultOutcome.Absent.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testResultReturnsRejectedWithTheServerCodeAndMessageOnACodedErrorStatus() throws Exception {
        HttpServer server = apiServer("/api/connections/", 403,
                "{\"code\":\"control.forbidden\",\"params\":{},\"message\":\"You lack the grade.\"}",
                new AtomicReference<>());
        try {
            ConnectionTestResultOutcome outcome =
                    new HttpControlPlaneClient().testResult(baseOf(server), "tok", "conn_ora");
            assertThat(outcome).isEqualTo(
                    new ConnectionTestResultOutcome.Rejected("control.forbidden", "You lack the grade."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testResultReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(new HttpControlPlaneClient()
                .testResult(URI.create("http://127.0.0.1:" + closedPort), "tok", "c"))
                .isInstanceOf(ConnectionTestResultOutcome.Unreachable.class);
    }

    // --- schema discovery: POST /api/connections:discover-schema, decodes the discovered model ---------

    private static final String SCHEMA_BODY =
            "{\"connectionId\":\"conn_ora\",\"connectorId\":\"oracle\",\"tables\":["
                    + "{\"name\":\"orders\",\"fields\":[{\"name\":\"id\",\"type\":\"NUMBER\"},"
                    + "{\"name\":\"note\",\"type\":null}],\"primaryKey\":[\"id\"],"
                    + "\"indexes\":[{\"name\":\"pk_orders\",\"fields\":[\"id\"],\"unique\":true}]}],"
                    + "\"discoveredAt\":1752000000000}";

    private static void assertDecodedSchema(ConnectionSchema schema) {
        assertThat(schema.connectionId()).isEqualTo("conn_ora");
        assertThat(schema.connectorId()).isEqualTo("oracle");
        assertThat(schema.discoveredAt()).isEqualTo(1752000000000L);
        assertThat(schema.tables()).containsExactly(new ConnectionSchema.Table(
                "orders",
                List.of(new ConnectionSchema.Field("id", "NUMBER"), new ConnectionSchema.Field("note", null)),
                List.of("id"),
                List.of(new ConnectionSchema.Index("pk_orders", List.of("id"), true))));
    }

    @Test
    void discoverSchemaPostsTheConnectionAndDecodesTheModel() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/connections:discover-schema", 200, SCHEMA_BODY, seen);
        try {
            ConnectionDiscoverSchemaOutcome outcome = new HttpControlPlaneClient()
                    .discoverSchema(baseOf(server), "tok-abc", "conn_ora", "oracle", Map.of("host", "10.20.0.15"));

            assertThat(outcome).isInstanceOf(ConnectionDiscoverSchemaOutcome.Discovered.class);
            assertDecodedSchema(((ConnectionDiscoverSchemaOutcome.Discovered) outcome).schema());

            // the request carried the connection id, its connector and settings under a bearer credential
            assertThat(seen.get().method()).isEqualTo("POST");
            assertThat(seen.get().path()).isEqualTo("/api/connections:discover-schema");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-abc");
            Map<?, ?> sent = (Map<?, ?>) JsonReader.parse(seen.get().body());
            assertThat(sent.get("id")).isEqualTo("conn_ora");
            assertThat(sent.get("connectorId")).isEqualTo("oracle");
            assertThat(sent.get("settings")).isEqualTo(Map.of("host", "10.20.0.15"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoverSchemaReturnsRejectedWithTheServerCodeAndMessageOnACodedErrorStatus() throws Exception {
        HttpServer server = apiServer("/api/connections:discover-schema", 403,
                "{\"code\":\"control.forbidden\",\"params\":{},\"message\":\"You lack the grade.\"}",
                new AtomicReference<>());
        try {
            ConnectionDiscoverSchemaOutcome outcome = new HttpControlPlaneClient()
                    .discoverSchema(baseOf(server), "tok", "conn_ora", "oracle", Map.of());
            assertThat(outcome).isEqualTo(
                    new ConnectionDiscoverSchemaOutcome.Rejected("control.forbidden", "You lack the grade."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoverSchemaReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(new HttpControlPlaneClient()
                .discoverSchema(URI.create("http://127.0.0.1:" + closedPort), "tok", "c", "oracle", Map.of()))
                .isInstanceOf(ConnectionDiscoverSchemaOutcome.Unreachable.class);
    }

    // --- schema read-back: GET /api/connections/{id}/schema, decodes the stored model -------------------

    @Test
    void schemaReturnsFoundWithTheStoredModelAndSendsTheCredential() throws Exception {
        AtomicReference<CapturedRequest> seen = new AtomicReference<>();
        HttpServer server = apiServer("/api/connections/", 200, SCHEMA_BODY, seen);
        try {
            ConnectionSchemaOutcome outcome =
                    new HttpControlPlaneClient().schema(baseOf(server), "tok-xyz", "conn_ora");

            assertThat(outcome).isInstanceOf(ConnectionSchemaOutcome.Found.class);
            assertDecodedSchema(((ConnectionSchemaOutcome.Found) outcome).schema());

            // the read is a GET to the connection's schema path, under a bearer credential
            assertThat(seen.get().method()).isEqualTo("GET");
            assertThat(seen.get().path()).isEqualTo("/api/connections/conn_ora/schema");
            assertThat(seen.get().authorization()).isEqualTo("Bearer tok-xyz");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void schemaSkipsAMalformedTableEntryAndKeepsTheRest() throws Exception {
        // one nameless table entry among good ones is skipped — the same lenient policy a malformed
        // check gets in a test report — rather than discarding the whole model
        String body = "{\"connectionId\":\"conn_ora\",\"connectorId\":\"oracle\",\"tables\":["
                + "{\"fields\":[]},"
                + "{\"name\":\"orders\",\"fields\":[],\"primaryKey\":[],\"indexes\":[]}],"
                + "\"discoveredAt\":1}";
        HttpServer server = apiServer("/api/connections/", 200, body, new AtomicReference<>());
        try {
            ConnectionSchemaOutcome outcome = new HttpControlPlaneClient().schema(baseOf(server), "tok", "conn_ora");

            assertThat(outcome).isInstanceOf(ConnectionSchemaOutcome.Found.class);
            ConnectionSchema schema = ((ConnectionSchemaOutcome.Found) outcome).schema();
            assertThat(schema.tables()).extracting(ConnectionSchema.Table::name).containsExactly("orders");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void schemaReturnsAbsentOnA404() throws Exception {
        // a 404 is "never discovered", distinct from a coded refusal
        HttpServer server = apiServer("/api/connections/", 404, null, new AtomicReference<>());
        try {
            assertThat(new HttpControlPlaneClient().schema(baseOf(server), "tok", "never_discovered"))
                    .isInstanceOf(ConnectionSchemaOutcome.Absent.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void schemaReturnsRejectedWithTheServerCodeAndMessageOnACodedErrorStatus() throws Exception {
        HttpServer server = apiServer("/api/connections/", 403,
                "{\"code\":\"control.forbidden\",\"params\":{},\"message\":\"You lack the grade.\"}",
                new AtomicReference<>());
        try {
            ConnectionSchemaOutcome outcome = new HttpControlPlaneClient().schema(baseOf(server), "tok", "conn_ora");
            assertThat(outcome).isEqualTo(
                    new ConnectionSchemaOutcome.Rejected("control.forbidden", "You lack the grade."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void schemaReturnsUnreachableWhenTheServerIsDownWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(new HttpControlPlaneClient()
                .schema(URI.create("http://127.0.0.1:" + closedPort), "tok", "c"))
                .isInstanceOf(ConnectionSchemaOutcome.Unreachable.class);
    }
}
