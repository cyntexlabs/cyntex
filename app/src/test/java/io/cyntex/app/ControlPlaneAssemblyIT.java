package io.cyntex.app;

import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.canonical.CanonicalWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole control plane assembled over a real store, end to end through HTTP: the assembly root brings up
 * the store, the authentication ports over it, the control-core services and the authenticated {@code /api}
 * face; a caller then bootstraps the first admin over loopback, signs in for a session token, applies an
 * artifact, and reads it back — each guarded step succeeding, and an unauthenticated request refused. This
 * witnesses {@link ControlPlaneConfiguration} wired against a genuine replica-set. Skipped automatically
 * where Docker is absent, so a Docker-less build stays green.
 */
@Testcontainers(disabledWithoutDocker = true)
class ControlPlaneAssemblyIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

    @Container
    private static final MongoDBContainer REPLICA_SET = new MongoDBContainer(MONGO_IMAGE);

    private ConfigurableApplicationContext context;

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void bootstrapThenLoginThenApplyThenReadBackOverARealStore() {
        int port = start();
        RestClient client = RestClient.create("http://localhost:" + port);

        // Anonymous: the verb surface is guarded from the first request.
        HttpStatusCode anonymous = client.get().uri("/api/artifacts")
                .exchange((request, response) -> response.getStatusCode());
        assertThat(anonymous).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Bootstrap the first admin over loopback (the test client is loopback).
        HttpStatusCode bootstrap = client.post().uri("/auth/bootstrap")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "admin", "password", "s3cret"))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(bootstrap).isEqualTo(HttpStatus.NO_CONTENT);

        // Sign in for a session token.
        Map<?, ?> login = client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "admin", "password", "s3cret"))
                .retrieve().body(Map.class);
        String token = (String) login.get("token");
        assertThat(token).isNotNull();

        // Apply an artifact with the session token, then read it back as its canonical form.
        HttpStatusCode applied = client.post().uri("/api/artifacts:apply")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("drafts", List.of(Map.of("content", SOURCE))))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(applied).isEqualTo(HttpStatus.OK);

        Map<?, ?> got = client.get().uri("/api/artifacts/src_ora")
                .header("Authorization", "Bearer " + token)
                .retrieve().body(Map.class);
        assertThat(got.get("id")).isEqualTo("src_ora");
        assertThat(got.get("canonicalForm")).isEqualTo(offlineCanonical(SOURCE));
    }

    @Test
    void connectionTestIsWiredThroughToTheConnectorRegistryOverARealStore() {
        int port = start();
        RestClient client = RestClient.create("http://localhost:" + port);

        // Bootstrap the first admin over loopback and sign in; the admin session covers the write verb.
        client.post().uri("/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "admin", "password", "s3cret")).retrieve().toBodilessEntity();
        Map<?, ?> login = client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "admin", "password", "s3cret")).retrieve().body(Map.class);
        String token = (String) login.get("token");

        // The whole connection plane is assembled over the real store: control verb -> runtime probe ->
        // adapter-pdk tester -> provisioner -> connector registry. Testing a connector that was never
        // registered resolves to no artifact and comes back as the coded connector-domain refusal, proving
        // the chain reaches the registry rather than a stub.
        Map<?, ?> body = client.post().uri("/api/connections:test")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("id", "conn_x", "connectorId", "never_registered", "settings", Map.of()))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    return response.bodyTo(Map.class);
                });
        assertThat(body.get("code")).isEqualTo("connector.not-registered");
        assertThat(((Map<?, ?>) body.get("params")).get("connector")).isEqualTo("never_registered");
    }

    private int start() {
        context = new SpringApplicationBuilder(AssemblyApp.class)
                .properties(
                        "server.port=0",
                        "cyntex.store.mongo.enabled=true",
                        "cyntex.store.mongo.uri=" + REPLICA_SET.getReplicaSetUrl(),
                        // the container speaks plaintext; TLS is opt-in, so no flag is needed here
                        "cyntex.store.mongo.server-selection-timeout=5s")
                .run();
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    /** The offline canonical contract for a draft: the exact bytes the authoring corpus golden locks. */
    private static String offlineCanonical(String draft) {
        return new CanonicalWriter().write(new DslParser().parse(draft));
    }

    private static final String SOURCE = """
            version: cyntex/v1
            kind: source
            id: src_ora
            connector: oracle
            config: { host: 10.20.0.15 }
            """;

    /**
     * The store bridge and the control plane, assembled without the rest of the process (no Hazelcast
     * member) so the integration stays focused on the store-backed control plane over HTTP.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({StoreConfiguration.class, ControlPlaneConfiguration.class})
    static class AssemblyApp {
    }
}
