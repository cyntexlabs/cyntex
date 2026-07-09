package io.cyntex.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

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
}
