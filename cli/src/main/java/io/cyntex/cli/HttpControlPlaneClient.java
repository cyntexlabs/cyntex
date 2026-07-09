package io.cyntex.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The production {@link ControlPlaneClient}, backed by the JDK HTTP client (no third-party
 * dependency, so rule R6 holds and the native image needs no extra metadata). Probes are given a
 * short connect and request timeout so an unreachable seed fails fast, and every failure mode —
 * connection refused, timeout, unknown host — resolves to "not healthy" rather than throwing, so the
 * caller can walk the seed list without try/catch.
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
        String base = baseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/healthz"))
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = client().send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
