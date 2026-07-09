package io.cyntex.cli;

import io.cyntex.core.common.JsonReader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
