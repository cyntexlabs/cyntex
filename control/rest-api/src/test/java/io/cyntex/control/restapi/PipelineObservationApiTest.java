package io.cyntex.control.restapi;

import io.cyntex.control.core.ControlOperations;
import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.control.core.Frontend;
import io.cyntex.control.core.GeneratedSecret;
import io.cyntex.control.core.Maturity;
import io.cyntex.control.core.Operation;
import io.cyntex.control.core.OperationRegistry;
import io.cyntex.control.core.PipelineMetrics;
import io.cyntex.control.core.PipelineObservationQueryService;
import io.cyntex.control.core.PipelineSnapshot;
import io.cyntex.control.core.PipelineStatus;
import io.cyntex.control.core.Scope;
import io.cyntex.control.core.TokenSecrets;
import io.cyntex.control.core.TokenService;
import io.cyntex.control.core.TokenSigner;
import io.cyntex.control.core.VerifiedToken;
import io.cyntex.core.lifecycle.Observation;
import io.cyntex.core.lifecycle.PipelineState;
import io.cyntex.core.lifecycle.TableSnapshot;
import io.cyntex.spi.store.ObservationStore;
import io.cyntex.spi.store.TokenRecord;
import io.cyntex.spi.store.TokenStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three store-backed observation read faces projected onto the authenticated {@code /api} surface:
 * status / metrics / snapshot, each a {@code GET} on a pipeline instance ({@code GET
 * /api/pipelines/{id}/status}). It proves the reads round-trip through the real query service (over an
 * in-memory observation store), that a read of a pipeline that has published no observation surfaces as a
 * 404 with the {@code monitor.no-observation} coded body rather than a bare 500, that the interceptor
 * guards a read like any other verb, and that the three read endpoints are a derivation of the registry.
 * The context is booted programmatically so the module stays on the reactor's JUnit line; it imports the
 * read controller with the path configuration and the interceptor, so it exercises the guarded read path.
 */
class PipelineObservationApiTest {

    private static final Instant NOW = Instant.parse("2026-07-12T12:00:00Z");

    /** One running pipeline with a couple of metrics and one in-progress snapshot table. */
    private static final Observation PL1 = new Observation("pl1", PipelineState.RUNNING,
            Map.of("recordCount", 42L, "errorCount", 0L),
            Map.of("orders", new TableSnapshot(10, 100L, 10)));

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void startServer() {
        context = new SpringApplicationBuilder(TestApp.class).properties("server.port=0").run();
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetStore() {
        FakeObservationStore observations = context.getBean(FakeObservationStore.class);
        observations.clear();
        observations.save(PL1);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    /** Mints a machine token of the given grade through the real token service and returns the bearer string. */
    private String machineToken(Scope scope) {
        return context.getBean(TokenService.class).create(scope);
    }

    // ---- the three read faces round-trip through the query service ----

    @Test
    void statusReturnsTheLifecycleStateForAReadCredential() {
        PipelineStatus body = client().get().uri("/api/pipelines/pl1/status")
                .header("Authorization", "Bearer " + machineToken(Scope.READ))
                .retrieve().toEntity(PipelineStatus.class).getBody();

        assertThat(body).isEqualTo(new PipelineStatus("pl1", PipelineState.RUNNING));
    }

    @Test
    void metricsReturnsTheOpenStatMap() {
        PipelineMetrics body = client().get().uri("/api/pipelines/pl1/metrics")
                .header("Authorization", "Bearer " + machineToken(Scope.READ))
                .retrieve().toEntity(PipelineMetrics.class).getBody();

        assertThat(body.pipelineId()).isEqualTo("pl1");
        assertThat(body.metrics()).containsEntry("recordCount", 42L).containsEntry("errorCount", 0L);
    }

    @Test
    void snapshotReturnsPerTableProgress() {
        PipelineSnapshot body = client().get().uri("/api/pipelines/pl1/snapshot")
                .header("Authorization", "Bearer " + machineToken(Scope.READ))
                .retrieve().toEntity(PipelineSnapshot.class).getBody();

        assertThat(body.pipelineId()).isEqualTo("pl1");
        assertThat(body.snapshot()).containsEntry("orders", new TableSnapshot(10, 100L, 10));
    }

    // ---- a read of a pipeline with no published observation is a 404 coded body, never a bare 500 ----

    @Test
    void aReadWithNoObservationIsNotFoundWithACodedBody() {
        ApiError body = client().get().uri("/api/pipelines/ghost/status")
                .header("Authorization", "Bearer " + machineToken(Scope.READ))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("monitor.no-observation");
        assertThat(body.params()).containsEntry("pipeline", "ghost");
    }

    // ---- the interceptor guards a read like any other verb ----

    @Test
    void anUnauthenticatedReadIsUnauthorized() {
        ApiError body = client().get().uri("/api/pipelines/pl1/status")
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.unauthenticated");
    }

    // ---- the read endpoints are a derivation of the registry ----

    @Test
    void theThreeReadFacesProjectRegisteredCliExposedVerbs() {
        Set<String> cliExposed = ControlOperations.registry()
                .exposedOn(Frontend.CLI, Maturity.POC).stream()
                .map(Operation::id).collect(Collectors.toSet());

        RequestMappingHandlerMapping mapping =
                context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);

        List<String> projectedReadFaces = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            Verb verb = handler.getMethodAnnotation(Verb.class);
            if (verb != null && verb.value().startsWith("pipeline.")) {
                projectedReadFaces.add(verb.value());
                assertThat(cliExposed)
                        .as("a projected observation read face must be a registered, CLI-exposed operation")
                        .contains(verb.value());
            }
        });

        assertThat(projectedReadFaces)
                .as("the three observation read faces project onto the authenticated /api surface")
                .containsExactlyInAnyOrder("pipeline.status", "pipeline.metrics", "pipeline.snapshot");
    }

    /**
     * A focused boot config: auto-configures Web MVC + the embedded servlet container, imports the path
     * configuration, the read controller and the coded-error advice, and supplies the {@link AuthInterceptor}
     * (so the read surface is guarded) over an in-memory token graph. The observation read side is the real
     * {@link PipelineObservationQueryService} over a fake observation store.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({RestApiConfiguration.class, PipelineObservationController.class, ApiExceptionHandler.class})
    static class TestApp {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        FakeObservationStore observationStore() {
            return new FakeObservationStore();
        }

        @Bean
        PipelineObservationQueryService pipelineObservationQueryService(ObservationStore observations) {
            return new PipelineObservationQueryService(observations);
        }

        @Bean
        FakeTokenStore tokenStore() {
            return new FakeTokenStore();
        }

        @Bean
        TokenSecrets tokenSecrets() {
            return new FakeTokenSecrets();
        }

        @Bean
        TokenSigner tokenSigner() {
            return new FakeSigner();
        }

        @Bean
        TokenService tokenService(TokenStore store, TokenSecrets secrets, Clock clock) {
            return new TokenService(store, secrets, clock);
        }

        @Bean
        OperationRegistry operationRegistry() {
            return ControlOperations.registry();
        }

        @Bean
        CredentialAuthenticator credentialAuthenticator(TokenService tokens, TokenSigner signer) {
            return new CredentialAuthenticator(tokens, signer);
        }

        @Bean
        AuthInterceptor authInterceptor(OperationRegistry registry, CredentialAuthenticator credentials) {
            return new AuthInterceptor(registry, credentials);
        }
    }

    // ---- fakes ----

    /** An in-memory observation store, last write wins per pipeline id, seedable. */
    static final class FakeObservationStore implements ObservationStore {
        private final Map<String, Observation> byId = new LinkedHashMap<>();

        void clear() {
            byId.clear();
        }

        @Override
        public void save(Observation observation) {
            byId.put(observation.pipelineId(), observation);
        }

        @Override
        public Optional<Observation> read(String pipelineId) {
            return Optional.ofNullable(byId.get(pipelineId));
        }
    }

    /** An in-memory token store keyed by token id. */
    static final class FakeTokenStore implements TokenStore {
        private final Map<String, TokenRecord> byId = new LinkedHashMap<>();

        @Override
        public void save(TokenRecord record) {
            byId.put(record.tokenId(), record);
        }

        @Override
        public Optional<TokenRecord> find(String tokenId) {
            return Optional.ofNullable(byId.get(tokenId));
        }

        @Override
        public void revoke(String tokenId) {
            TokenRecord existing = byId.get(tokenId);
            if (existing != null) {
                byId.put(tokenId, new TokenRecord(existing.tokenId(), existing.scope(),
                        existing.secretHash(), true, existing.createdAt()));
            }
        }

        @Override
        public List<TokenRecord> list() {
            return new ArrayList<>(byId.values());
        }
    }

    /** A deterministic secret minter: tok-N / sec-N with a reversible hash. */
    static final class FakeTokenSecrets implements TokenSecrets {
        private int counter;

        @Override
        public GeneratedSecret generate() {
            counter++;
            return new GeneratedSecret("tok-" + counter, "sec-" + counter, "hash:sec-" + counter);
        }

        @Override
        public boolean matches(String presentedSecret, String storedHash) {
            return storedHash.equals("hash:" + presentedSecret);
        }
    }

    /** A signer whose token is a reversible {@code subject|SCOPE} encoding. */
    static final class FakeSigner implements TokenSigner {
        @Override
        public String issue(String subject, Scope scope) {
            return subject + "|" + scope.name();
        }

        @Override
        public Optional<VerifiedToken> verify(String token) {
            int bar = token.indexOf('|');
            if (bar < 0) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedToken(token.substring(0, bar), Scope.valueOf(token.substring(bar + 1))));
        }
    }
}
