package io.cyntex.e2e;

import io.cyntex.core.common.JsonReader;
import io.cyntex.core.common.JsonWriter;
import io.cyntex.core.lifecycle.LifecycleVerb;
import io.cyntex.core.lifecycle.PipelineState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The product's HTTP surface, as a caller sees it.
 *
 * <p>The harness speaks this wire itself rather than borrowing the CLI's client: the CLI's own
 * testing is unit tests, a corpus and a native smoke, and pulling it in here would make every
 * specification a test of two things at once. What is shared with the product on purpose is the JSON
 * codec and the DSL parser - the places where a second implementation would be a second truth.
 *
 * <p>Failures are surfaced, never absorbed: a refused verb fails the specification carrying the
 * server's own status and body, because a harness that turns a 4xx into a quiet nothing would let a
 * broken product pass.
 */
final class ControlPlane {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final URI baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private String credential;

    ControlPlane(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Whether the product answers its health probe; the readiness signal a launcher waits on. */
    boolean healthy() {
        try {
            HttpResponse<String> response = send(get("/healthz"));
            return response.statusCode() == 200 && "ok".equals(response.body());
        } catch (UncheckedIOException e) {
            return false;
        }
    }

    /**
     * Creates the first admin and holds its token for every later call. Bootstrap is refused off
     * loopback, which is why both tiers run the server on this machine rather than in a container.
     */
    void bootstrapAndLogin(String username, String password) {
        String body = JsonWriter.write(Map.of("username", username, "password", password));
        expect(send(post("/auth/bootstrap", body)), 204, "bootstrap the first admin");
        HttpResponse<String> login = send(post("/auth/login", body));
        expect(login, 200, "log in");
        if (!(JsonReader.parse(login.body()) instanceof Map<?, ?> map)
                || !(map.get("token") instanceof String token)) {
            throw new AssertionError("login returned no token: " + login.body());
        }
        credential = token;
    }

    /**
     * Applies resource documents as one batch, each named by the file it came from. One call, not one
     * per file: the product resolves references within the submitted set, so resources that name each
     * other have to be submitted together.
     */
    void apply(Map<String, String> contentBySource) {
        List<Map<String, String>> drafts = contentBySource.entrySet().stream()
                .map(entry -> Map.of("source", entry.getKey(), "content", entry.getValue()))
                .toList();
        String body = JsonWriter.write(Map.of("drafts", drafts));
        expect(send(authed("/api/artifacts:apply", body)), 200, "apply " + contentBySource.keySet());
    }

    /** The ids the server holds - read back from the server, which is the truth, not from the files sent. */
    List<String> artifactIds() {
        HttpResponse<String> response = send(authedGet("/api/artifacts"));
        expect(response, 200, "list artifacts");
        if (!(JsonReader.parse(response.body()) instanceof Map<?, ?> map)
                || !(map.get("artifacts") instanceof List<?> artifacts)) {
            throw new AssertionError("artifact list was not a list: " + response.body());
        }
        return artifacts.stream()
                .map(each -> each instanceof Map<?, ?> m ? m.get("id") : null)
                .map(String::valueOf)
                .toList();
    }

    /** Registers a connector's runtime jar; the product makes this idempotent by content hash. */
    void registerConnector(String connectorId, byte[] jar) {
        String body = JsonWriter.write(Map.of("artifact", Base64.getEncoder().encodeToString(jar)));
        expect(send(authed("/api/connectors:register", body)), 200, "register the " + connectorId + " connector");
    }

    /** Discovers a source's model, which is what a target table is later derived from. */
    void discoverSchema(String resourceId, String connectorId, Map<String, Object> settings) {
        String body = JsonWriter.write(
                Map.of("id", resourceId, "connectorId", connectorId, "settings", settings));
        expect(send(authed("/api/connections:discover-schema", body)), 200, "discover the model of " + resourceId);
    }

    /**
     * Records a lifecycle intent. The verb's own spelling comes from the product's enum, so the wire
     * word cannot drift from the word the product accepts.
     */
    void lifecycle(String pipelineId, LifecycleVerb verb) {
        expect(send(authed("/api/pipelines/" + pipelineId + ":" + verb.id(), "")),
                200, verb.id() + " " + pipelineId);
    }

    /**
     * The published lifecycle state. Before the first convergence pass there is no observation at all,
     * and the product says so with a coded 404; reporting that as some state would invent a reading.
     */
    PipelineState state(String pipelineId) {
        HttpResponse<String> response = send(authedGet("/api/pipelines/" + pipelineId + "/status"));
        expect(response, 200, "read the status of " + pipelineId);
        if (!(JsonReader.parse(response.body()) instanceof Map<?, ?> map)
                || !(map.get("state") instanceof String state)) {
            throw new AssertionError("status carried no state: " + response.body());
        }
        return PipelineState.valueOf(state);
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(TIMEOUT).GET().build();
    }

    private HttpRequest authedGet(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + requireCredential())
                .GET()
                .build();
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest authed(String path, String body) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + requireCredential())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private String requireCredential() {
        if (credential == null) {
            throw new IllegalStateException("no credential: log in before driving an authenticated verb");
        }
        return credential;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while calling " + request.uri(), e);
        }
    }

    private static void expect(HttpResponse<String> response, int status, String what) {
        if (response.statusCode() != status) {
            throw new AssertionError(
                    "could not " + what + ": expected HTTP " + status + ", got " + response.statusCode()
                            + " - " + response.body());
        }
    }
}
