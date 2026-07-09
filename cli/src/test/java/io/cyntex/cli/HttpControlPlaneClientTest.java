package io.cyntex.cli;

import com.sun.net.httpserver.HttpServer;
import io.cyntex.core.common.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    void loginTreatsANonCodedErrorBodyAsARejectionWithoutCrashing() throws Exception {
        // a non-JSON error body (e.g. a container 500 page) must not crash login; it is still a refusal
        HttpServer server = loginServer(500, "<html>Internal Server Error</html>", new AtomicReference<>());
        try {
            LoginOutcome outcome = new HttpControlPlaneClient().login(baseOf(server), "a", "b");
            assertThat(outcome).isInstanceOf(LoginOutcome.Rejected.class);
        } finally {
            server.stop(0);
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
}
