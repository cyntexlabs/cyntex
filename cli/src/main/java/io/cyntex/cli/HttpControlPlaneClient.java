package io.cyntex.cli;

import io.cyntex.core.common.JsonReader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

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
            return rejected(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LoginOutcome.Unreachable();
        } catch (IOException | RuntimeException e) {
            return new LoginOutcome.Unreachable();
        }
    }

    /** The absolute request URI for {@code path} against a base, tolerating a trailing slash on the base. */
    private static URI endpoint(URI baseUrl, String path) {
        String base = baseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    /** Turns a non-200 login response body into a coded rejection, or a generic one if it is not coded. */
    private static LoginOutcome rejected(String body) {
        try {
            if (JsonReader.parse(body) instanceof Map<?, ?> map && map.get("code") instanceof String code) {
                String message = map.get("message") instanceof String m ? m : code;
                return new LoginOutcome.Rejected(code, message);
            }
        } catch (RuntimeException malformed) {
            // fall through: a non-coded / unparseable error body is still a refusal, not a crash
        }
        return new LoginOutcome.Rejected("", "Login was refused by the server.");
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
}
