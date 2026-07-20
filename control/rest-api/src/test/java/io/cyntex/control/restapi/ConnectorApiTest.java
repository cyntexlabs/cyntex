package io.cyntex.control.restapi;

import io.cyntex.control.core.AuditGate;
import io.cyntex.control.core.ConnectorCatalogView;
import io.cyntex.control.core.ConnectorRegisterService;
import io.cyntex.control.core.ConnectorRegistrationReport;
import io.cyntex.control.core.ConnectorSummary;
import io.cyntex.control.core.ControlOperations;
import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.core.catalog.CatalogEntryReader;
import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.control.core.GeneratedSecret;
import io.cyntex.control.core.OperationRegistry;
import io.cyntex.control.core.Scope;
import io.cyntex.control.core.TokenSecrets;
import io.cyntex.control.core.TokenService;
import io.cyntex.control.core.TokenSigner;
import io.cyntex.control.core.VerifiedToken;
import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.common.Severity;
import io.cyntex.spi.store.AuditRecord;
import io.cyntex.spi.store.AuditStore;
import io.cyntex.spi.store.ConnectorCatalogStore;
import io.cyntex.spi.store.ConnectorRegistrar;
import io.cyntex.spi.store.ConnectorRegistration;
import io.cyntex.spi.store.ContentHash;
import io.cyntex.spi.store.RegistrationOutcome;
import io.cyntex.spi.store.RegistrationSource;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connector register verb projected onto HTTP, exercised end to end through a real embedded server: an
 * authenticated caller posts a base64-encoded connector artifact, the controller decodes it, drives it
 * through the (fake) registrar under the audit gate, and returns the registration report.
 *
 * <p>The registrar is an in-memory fake so the test needs no connector or PDK; the authentication stack, the
 * audit gate and the controller wiring are real, so the principal the operation is audited to is the
 * authenticated session's — read from the guard, not the request body — and the audited resource is the
 * artifact's content hash. The context is booted programmatically so the module stays on the reactor's
 * JUnit line.
 */
class ConnectorApiTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final byte[] ARTIFACT = "orders-connector-jar-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String ARTIFACT_B64 = Base64.getEncoder().encodeToString(ARTIFACT);

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
    void resetStores() {
        context.getBean(FakeConnectorRegistrar.class).clear();
        context.getBean(RecordingAuditStore.class).clear();
        context.getBean(FakeTokenStore.class).clear();
        context.getBean(SeedableConnectorCatalogStore.class).clear();
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private String token(Scope scope) {
        return context.getBean(TokenService.class).create(scope);
    }

    private static Map<String, Object> registerBody(String artifactBase64) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifact", artifactBase64);
        return body;
    }

    // ---- the verb decodes the artifact, drives the registrar, and projects the outcome onto HTTP ----

    @Test
    void registersThePostedArtifactAndReturnsItsReport() {
        ConnectorRegistrationReport report = client().post().uri("/api/connectors:register")
                .header("Authorization", "Bearer " + token(Scope.WRITE))
                .contentType(MediaType.APPLICATION_JSON)
                .body(registerBody(ARTIFACT_B64))
                .retrieve().toEntity(ConnectorRegistrationReport.class).getBody();

        // The response is the control-ring report of what was registered.
        assertThat(report.connectorId()).isEqualTo("orders");
        assertThat(report.pdkApiVersion()).isEqualTo("1.3.5");
        assertThat(report.newlyRegistered()).isTrue();

        // The controller decoded the base64 body to exactly the posted bytes and drove the registrar with
        // them under the explicit runtime register source.
        FakeConnectorRegistrar registrar = context.getBean(FakeConnectorRegistrar.class);
        assertThat(registrar.received()).isEqualTo(ARTIFACT);
        assertThat(registrar.source()).isEqualTo(RegistrationSource.REGISTER);
    }

    @Test
    void reportsAnAlreadyRegisteredArtifactAsNotNewlyRegistered() {
        context.getBean(FakeConnectorRegistrar.class).returnsNewlyRegistered(false);

        ConnectorRegistrationReport report = client().post().uri("/api/connectors:register")
                .header("Authorization", "Bearer " + token(Scope.WRITE))
                .contentType(MediaType.APPLICATION_JSON)
                .body(registerBody(ARTIFACT_B64))
                .retrieve().toEntity(ConnectorRegistrationReport.class).getBody();

        assertThat(report.newlyRegistered()).isFalse();
        assertThat(report.connectorId()).isEqualTo("orders");
    }

    @Test
    void isAuditedToTheAuthenticatedPrincipalAgainstTheArtifactContentHash() {
        String bearer = token(Scope.WRITE);
        String expectedPrincipal =
                context.getBean(CredentialAuthenticator.class).authenticate(bearer).orElseThrow().subject();

        client().post().uri("/api/connectors:register")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(registerBody(ARTIFACT_B64))
                .retrieve().toBodilessEntity();

        assertThat(context.getBean(RecordingAuditStore.class).records()).singleElement().satisfies(record -> {
            assertThat(record.operationId()).isEqualTo("connector.register");
            assertThat(record.principal()).isEqualTo(expectedPrincipal);
            assertThat(record.resourceId()).isEqualTo(ContentHash.of(ARTIFACT));
        });
    }

    // ---- the read peer lists the online catalog, registered rows included and tagged ----

    private static final String ORDERS_ROW = """
            {
              "id": "orders", "name": "Orders", "displayName": "Orders", "icon": null,
              "group": "database", "modes": ["snapshot"], "discovery": "catalog",
              "sink": {"capable": false, "writeSemantics": []}, "pushOut": false,
              "config": [
                {"name": "authType", "type": "string", "label": {"en_US": "Authentication"},
                  "required": false, "default": "password", "secret": false,
                  "options": [{"value": "password", "label": {"en_US": "Password"}}],
                  "visibleWhen": null},
                {"name": "password", "type": "string", "label": {"en_US": "Password"},
                  "required": false, "default": null, "secret": true, "options": [],
                  "visibleWhen": {"controllingField": "authType", "equalsAnyOf": ["password"]}}
              ],
              "provenance": {"connectorRepoSha": null, "specPath": "spec.json", "specContentHash": "h",
                "pdkApiVersion": "1.3.5", "requiredLevel": null, "modeSource": {"snapshot": "derived"}}
            }
            """;

    @Test
    void listsTheOnlineCatalogWithRegisteredRowsTaggedRegistered() {
        context.getBean(SeedableConnectorCatalogStore.class).upsert(CatalogEntryReader.read(ORDERS_ROW));

        ConnectorCatalogList listed = client().get().uri("/api/connectors")
                .header("Authorization", "Bearer " + token(Scope.READ))
                .retrieve().toEntity(ConnectorCatalogList.class).getBody();

        ConnectorSummary orders = listed.connectors().stream()
                .filter(c -> c.id().equals("orders")).findFirst().orElseThrow();
        assertThat(orders.origin()).isEqualTo("registered");
        assertThat(orders.modes()).contains("snapshot");
    }

    @Test
    void listingRequiresAnAuthenticatedCaller() {
        HttpStatusCode status = client().get().uri("/api/connectors")
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getsOneConnectorWithItsNormalizedConfig() {
        context.getBean(SeedableConnectorCatalogStore.class).upsert(CatalogEntryReader.read(ORDERS_ROW));

        Map<?, ?> detail = client().get().uri("/api/connectors/orders")
                .header("Authorization", "Bearer " + token(Scope.READ))
                .retrieve().body(Map.class);

        assertThat(detail.get("id")).isEqualTo("orders");
        assertThat(detail.get("origin")).isEqualTo("registered");
        List<?> config = (List<?>) detail.get("config");
        assertThat(config).hasSize(2);
        Map<?, ?> password = (Map<?, ?>) config.get(1);
        assertThat(password.get("name")).isEqualTo("password");
        assertThat(password.get("type")).isEqualTo("string");
        assertThat(password.get("label")).isEqualTo("Password");
        assertThat(password.get("secret")).isEqualTo(true);
        assertThat(password.containsKey("x-component")).isFalse();
        assertThat(password.containsKey("x-reactions")).isFalse();
    }

    @Test
    void gettingAnUnknownConnectorReturnsACodedNotFound() {
        ApiError body = client().get().uri("/api/connectors/missing")
                .header("Authorization", "Bearer " + token(Scope.READ))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("connector.not-found");
        assertThat(body.params()).containsEntry("connector", "missing");
    }

    // ---- the verb is a write, guarded like every other ----

    @Test
    void requiresAWriteCredential() {
        ApiError body = client().post().uri("/api/connectors:register")
                .header("Authorization", "Bearer " + token(Scope.READ))
                .contentType(MediaType.APPLICATION_JSON)
                .body(registerBody(ARTIFACT_B64))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.forbidden");
        assertThat(body.params()).containsEntry("op", "connector.register").containsEntry("required", "write");
        // A refused write must not reach the registrar at all.
        assertThat(context.getBean(FakeConnectorRegistrar.class).received()).isNull();
    }

    @Test
    void anUnauthenticatedCallerCannotReachTheVerb() {
        HttpStatusCode status = client().post().uri("/api/connectors:register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(registerBody(ARTIFACT_B64))
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- a structurally malformed body is refused at the boundary, not deeper as a bare crash ----

    @Test
    void aMissingArtifactFieldIsABadRequestWithACodedBody() {
        ApiError body = client().post().uri("/api/connectors:register")
                .header("Authorization", "Bearer " + token(Scope.WRITE))
                .contentType(MediaType.APPLICATION_JSON)
                .body(registerBody(null))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.malformed-request");
        assertThat(body.params()).containsKey("reason");
        assertThat(context.getBean(FakeConnectorRegistrar.class).received()).isNull();
    }

    @Test
    void aNonBase64ArtifactIsABadRequestWithACodedBody() {
        ApiError body = client().post().uri("/api/connectors:register")
                .header("Authorization", "Bearer " + token(Scope.WRITE))
                .contentType(MediaType.APPLICATION_JSON)
                .body(registerBody("this is not valid base64 %%%"))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.malformed-request");
        assertThat(body.params()).containsKey("reason");
        assertThat(context.getBean(FakeConnectorRegistrar.class).received()).isNull();
    }

    // ---- a coded connector-domain refusal from register is a client-attributable 400 ----

    @Test
    void aCodedConnectorFailureFromRegisterIsABadRequestCarryingTheConnectorCode() {
        context.getBean(FakeConnectorRegistrar.class).fails(new CyntexException(
                new ConnectorCode("connector.spec-invalid", Set.of("artifact", "spec", "detail")),
                Map.of("artifact", "bad.jar", "spec", "spec.json", "detail", "the spec is not valid JSON"),
                null));

        ApiError body = client().post().uri("/api/connectors:register")
                .header("Authorization", "Bearer " + token(Scope.WRITE))
                .contentType(MediaType.APPLICATION_JSON)
                .body(registerBody(ARTIFACT_B64))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("connector.spec-invalid");
        assertThat(body.params()).containsEntry("artifact", "bad.jar");
    }

    /**
     * A minimal boot config: auto-configures Web MVC + the embedded servlet container, imports the path
     * prefix + interceptor registration, the connector controller and the coded-error advice, and supplies
     * the {@link AuthInterceptor} bean that engages the guard — so the verb surface is authenticated exactly
     * as in production. The register service is real, composed over an in-memory registrar and audit store.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({RestApiConfiguration.class, ConnectorController.class, ApiExceptionHandler.class})
    static class TestApp {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        OperationRegistry operationRegistry() {
            return ControlOperations.registry();
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
        CredentialAuthenticator credentialAuthenticator(TokenService tokens, TokenSigner signer) {
            return new CredentialAuthenticator(tokens, signer);
        }

        @Bean
        AuthInterceptor authInterceptor(OperationRegistry registry, CredentialAuthenticator credentials) {
            return new AuthInterceptor(registry, credentials);
        }

        @Bean
        RecordingAuditStore auditStore() {
            return new RecordingAuditStore();
        }

        @Bean
        AuditGate auditGate(AuditStore store, Clock clock) {
            return new AuditGate(store, clock);
        }

        @Bean
        FakeConnectorRegistrar connectorRegistrar() {
            return new FakeConnectorRegistrar();
        }

        @Bean
        ConnectorRegisterService connectorRegisterService(ConnectorRegistrar registrar, AuditGate auditGate) {
            return new ConnectorRegisterService(registrar, auditGate);
        }

        @Bean
        SeedableConnectorCatalogStore connectorCatalogStore() {
            return new SeedableConnectorCatalogStore();
        }

        @Bean
        ConnectorCatalogView connectorCatalogView(SeedableConnectorCatalogStore store) {
            return new ConnectorCatalogView(CyntexCatalog.load(), store);
        }
    }

    // ---- fakes ----

    /**
     * A registrar that captures the bytes and source it was driven with and returns a canned outcome, or
     * throws a preset coded failure — so the controller and audit wiring can be proven without a connector.
     */
    private static final class FakeConnectorRegistrar implements ConnectorRegistrar {
        private byte[] received;
        private RegistrationSource source;
        private boolean newlyRegistered = true;
        private RuntimeException failure;

        void clear() {
            received = null;
            source = null;
            newlyRegistered = true;
            failure = null;
        }

        void returnsNewlyRegistered(boolean value) {
            this.newlyRegistered = value;
        }

        void fails(RuntimeException failure) {
            this.failure = failure;
        }

        byte[] received() {
            return received;
        }

        RegistrationSource source() {
            return source;
        }

        @Override
        public RegistrationOutcome register(byte[] artifact, RegistrationSource source) {
            this.received = artifact;
            this.source = source;
            if (failure != null) {
                throw failure;
            }
            return new RegistrationOutcome(
                    new ConnectorRegistration("orders", ContentHash.of(artifact), "1.3.5", source), newlyRegistered);
        }
    }

    /** An in-memory connector catalog row store the list test seeds; cleared between tests. */
    private static final class SeedableConnectorCatalogStore implements ConnectorCatalogStore {
        private final Map<String, ConnectorCatalogEntry> rows = new LinkedHashMap<>();

        void clear() {
            rows.clear();
        }

        @Override
        public void upsert(ConnectorCatalogEntry entry) {
            rows.put(entry.id(), entry);
        }

        @Override
        public Optional<ConnectorCatalogEntry> get(String connectorId) {
            return Optional.ofNullable(rows.get(connectorId));
        }

        @Override
        public List<ConnectorCatalogEntry> list() {
            return new ArrayList<>(rows.values());
        }
    }

    /** A capturing audit sink, so the test can read back what the operation was audited to. */
    private static final class RecordingAuditStore implements AuditStore {
        private final List<AuditRecord> records = new ArrayList<>();

        void clear() {
            records.clear();
        }

        List<AuditRecord> records() {
            return records;
        }

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }
    }

    /** A stand-in connector-domain code, so this module can raise a connector.* coded failure without the PDK. */
    private record ConnectorCode(String code, Set<String> placeholders) implements CyntexErrorCode {
        @Override
        public Severity severity() {
            return Severity.ERROR;
        }
    }

    /** An in-memory token store keyed by token id. */
    private static final class FakeTokenStore implements TokenStore {
        private final Map<String, TokenRecord> byId = new LinkedHashMap<>();

        void clear() {
            byId.clear();
        }

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
    private static final class FakeTokenSecrets implements TokenSecrets {
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
    private static final class FakeSigner implements TokenSigner {
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
