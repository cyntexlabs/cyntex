package io.cyntex.control.restapi;

import io.cyntex.control.core.ApplyService;
import io.cyntex.control.core.ArtifactQueryService;
import io.cyntex.control.core.AuditGate;
import io.cyntex.control.core.BootstrapService;
import io.cyntex.control.core.ConnectionTestResultQueryService;
import io.cyntex.control.core.ConnectionTestService;
import io.cyntex.control.core.ControlOperations;
import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.control.core.LoginService;
import io.cyntex.control.core.OperationRegistry;
import io.cyntex.control.core.PasswordHasher;
import io.cyntex.control.core.Scope;
import io.cyntex.control.core.TokenService;
import io.cyntex.control.core.TokenSigner;
import io.cyntex.control.core.TokenSecrets;
import io.cyntex.control.core.GeneratedSecret;
import io.cyntex.control.core.VerifiedToken;
import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.runtime.probe.ConnectionProbe;
import io.cyntex.spi.store.AuditRecord;
import io.cyntex.spi.store.AuditStore;
import io.cyntex.spi.store.ConnectionTestResult;
import io.cyntex.spi.store.ConnectionTestResultStore;
import io.cyntex.spi.store.TokenRecord;
import io.cyntex.spi.store.TokenStore;
import io.cyntex.spi.store.User;
import io.cyntex.spi.store.UserStore;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authentication and authorization matrix over the {@code /api} verb surface, exercised end to end
 * through a real embedded server (booted programmatically so the module stays on the reactor's JUnit
 * line). It proves the interceptor guards every verb, the two credential mechanisms both authenticate,
 * the grade check refuses an under-scoped caller, and the two pre-authentication entry points (login and
 * the zero-user bootstrap) live outside the guard and self-guard. The stores and the crypto are in-memory
 * fakes; the control-core services and the wiring are real.
 */
class AuthTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");

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
        context.getBean(FakeUserStore.class).clear();
        context.getBean(FakeTokenStore.class).clear();
        context.getBean(InMemoryArtifactStore.class).clear();
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    /** Mints a machine token of the given grade through the real token service and returns the bearer string. */
    private String machineToken(Scope scope) {
        return context.getBean(TokenService.class).create(scope);
    }

    // ---- an unauthenticated caller cannot reach a verb ----

    @Test
    void aVerbWithoutACredentialIsUnauthorized() {
        ApiError body = client().get().uri("/api/artifacts")
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.unauthenticated");
        // The refusal echoes nothing back, so it cannot be used to probe which credentials exist.
        assertThat(body.params()).isEmpty();
    }

    @Test
    void anInvalidCredentialIsUnauthorized() {
        ApiError body = client().get().uri("/api/artifacts")
                .header("Authorization", "Bearer not-a-real-token")
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.unauthenticated");
    }

    // ---- the grade check refuses an under-scoped caller but admits a sufficient one ----

    @Test
    void aReadCredentialIsForbiddenFromAWriteVerb() {
        String readToken = machineToken(Scope.READ);

        ApiError body = client().post().uri("/api/artifacts:apply")
                .header("Authorization", "Bearer " + readToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("drafts", List.of(Map.of("content", SOURCE))))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.forbidden");
        assertThat(body.params()).containsEntry("op", "artifact.apply").containsEntry("required", "write");
    }

    @Test
    void aReadCredentialMayReadAVerb() {
        HttpStatusCode status = client().get().uri("/api/artifacts")
                .header("Authorization", "Bearer " + machineToken(Scope.READ))
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aWriteCredentialMayApply() {
        HttpStatusCode status = client().post().uri("/api/artifacts:apply")
                .header("Authorization", "Bearer " + machineToken(Scope.WRITE))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("drafts", List.of(Map.of("content", SOURCE))))
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theBearerSchemeIsMatchedCaseInsensitively() {
        // The auth-scheme name is case-insensitive (RFC 7235), so a lowercase "bearer" carries a valid credential.
        HttpStatusCode status = client().get().uri("/api/artifacts")
                .header("Authorization", "bearer " + machineToken(Scope.READ))
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.OK);
    }

    // ---- login is a pre-authentication entry point that issues a working credential ----

    @Test
    void loginIssuesASessionTokenThatAuthenticatesAVerb() {
        seedUser("alice", "s3cret", "admin");

        LoginResponse login = client().post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LoginRequest("alice", "s3cret"))
                .retrieve().toEntity(LoginResponse.class).getBody();

        assertThat(login.token()).isNotBlank();
        // The minted session token authenticates a verb on the guarded surface.
        HttpStatusCode status = client().get().uri("/api/artifacts")
                .header("Authorization", "Bearer " + login.token())
                .exchange((request, response) -> response.getStatusCode());
        assertThat(status).isEqualTo(HttpStatus.OK);
    }

    @Test
    void loginWithAWrongPasswordIsUnauthorizedWithoutLeak() {
        seedUser("alice", "s3cret", "admin");

        ApiError body = client().post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LoginRequest("alice", "wrong"))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.auth-failed");
        assertThat(body.params()).as("the login failure reveals nothing about which half was wrong").isEmpty();
    }

    @Test
    void loginWithABlankCredentialIsABadRequestWithACodedBody() {
        // A missing / blank credential field is a malformed request — the client omitted a required value —
        // refused at the boundary with a coded control.malformed-request (400). This is distinct from a
        // present-but-wrong credential (the auth-failed 401), and it never reaches the login service's bare
        // argument crash.
        ApiError body = client().post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LoginRequest("", "s3cret"))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.malformed-request");
        assertThat(body.params()).containsKey("reason");
    }

    // ---- the zero-user bootstrap channel: loopback-only, one-shot ----

    @Test
    void bootstrapCreatesTheFirstAdminFromLoopbackThenLetsItSignIn() {
        // The test client connects over loopback, so the bootstrap channel accepts it on the empty store.
        HttpStatusCode created = client().post().uri("/auth/bootstrap")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new BootstrapRequest("root", "s3cret"))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(created).isEqualTo(HttpStatus.NO_CONTENT);

        // The first admin now exists and can sign in.
        LoginResponse login = client().post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LoginRequest("root", "s3cret"))
                .retrieve().toEntity(LoginResponse.class).getBody();
        assertThat(login.token()).isNotBlank();
    }

    @Test
    void bootstrapIsClosedOnceAUserExists() {
        seedUser("existing", "pw", "admin");

        ApiError body = client().post().uri("/auth/bootstrap")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new BootstrapRequest("root", "s3cret"))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.bootstrap-closed");
    }

    @Test
    void bootstrapWithABlankCredentialIsABadRequestWithACodedBody() {
        // The test client is on loopback and the store is empty, so the bootstrap channel is open. A blank
        // password is still refused at the boundary with a coded control.malformed-request (400) — it would
        // otherwise be hashed into a non-blank hash and silently create an admin with an empty password.
        ApiError body = client().post().uri("/auth/bootstrap")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new BootstrapRequest("root", ""))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("control.malformed-request");
        assertThat(body.params()).containsKey("reason");
    }

    // ---- the liveness probe stays anonymous; topology does not ----

    @Test
    void theLivenessProbeStaysAnonymous() {
        String probe = client().get().uri("/healthz").retrieve().body(String.class);
        assertThat(probe).isEqualTo("ok");
    }

    @Test
    void topologyIsBehindAuthenticationNotAnonymous() {
        // cluster.members is a read verb reserved as a 501 stub; the point here is that it is reached only
        // after authentication — an anonymous caller is turned away at the interceptor with 401, never 501.
        HttpStatusCode anonymous = client().get().uri("/api/cluster/members")
                .exchange((request, response) -> response.getStatusCode());
        assertThat(anonymous).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpStatusCode authenticated = client().get().uri("/api/cluster/members")
                .header("Authorization", "Bearer " + machineToken(Scope.READ))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(authenticated).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    // ---- the unauthenticated surface is a closed allow-list (root-exit convergence) ----

    @Test
    void theOnlyEndpointsOutsideTheApiPrefixAreTheProbeAndThePreAuthEntryPoints() {
        // /api is the authenticated boundary (the interceptor guards /api/**). Everything reachable
        // anonymously must therefore be an intentional carve-out: the liveness probe, the two pre-auth
        // entry points, and the framework's own error endpoint (which renders only the current request's
        // error, no application data). A future plain @Controller added at the root would escape both the
        // verb-derivation gate and the interceptor — this pins the anonymous surface to exactly that set.
        Set<String> allowedRootPaths = Set.of("/healthz", "/auth/login", "/auth/bootstrap", "/error");

        RequestMappingHandlerMapping mapping =
                context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);

        List<String> unexpectedRootEndpoints = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                // Mirror the interceptor's /api/** exactly (segment-based), so a sibling like /api-internal
                // is treated as outside the guarded surface and must be allow-listed too, not slipped through
                // by a loose string prefix.
                boolean underApi = pattern.equals("/api") || pattern.startsWith("/api/");
                if (!underApi && !allowedRootPaths.contains(pattern)) {
                    unexpectedRootEndpoints.add(describe(handler) + " -> " + pattern);
                }
            }
        });

        assertThat(unexpectedRootEndpoints)
                .as("only the liveness probe and the pre-auth entry points may live outside /api; every "
                        + "other endpoint is a registry verb under the authenticated /api prefix")
                .isEmpty();
    }

    private static String describe(HandlerMethod handler) {
        return handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
    }

    private void seedUser(String username, String password, String role) {
        FakeHasher hasher = context.getBean(FakeHasher.class);
        context.getBean(FakeUserStore.class).save(new User(username, hasher.hash(password), role));
    }

    /** A source draft the apply verb accepts, so the write-path test reaches a real outcome. */
    private static final String SOURCE = """
            version: cyntex/v1
            kind: source
            id: src_ora
            connector: oracle
            config: { host: 10.20.0.15 }
            """;

    /**
     * A minimal boot config: auto-configures Web MVC + the embedded servlet container, imports the whole
     * HTTP control face as one bundle ({@link ControlHttpFace} — path prefix, interceptor registration,
     * every verb controller, the pre-auth controller, the probe, the advice, and the interceptor bean),
     * and constructs the real control-core services over in-memory fakes. Importing the bundle rather than
     * the parts means this test exercises the exact assembly the running server uses.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ControlHttpFace.class)
    static class TestApp {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        FakeUserStore userStore() {
            return new FakeUserStore();
        }

        @Bean
        FakeTokenStore tokenStore() {
            return new FakeTokenStore();
        }

        @Bean
        AuditStore auditStore() {
            // A no-op is enough here; the "no audit, no execute" gate contract is proven in control-core.
            return entry -> {
            };
        }

        @Bean
        InMemoryArtifactStore artifactStore() {
            return new InMemoryArtifactStore();
        }

        @Bean
        FakeHasher passwordHasher() {
            return new FakeHasher();
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
        LoginService loginService(UserStore users, PasswordHasher hasher, TokenSigner signer) {
            return new LoginService(users, hasher, signer);
        }

        @Bean
        AuditGate auditGate(AuditStore store, Clock clock) {
            return new AuditGate(store, clock);
        }

        @Bean
        BootstrapService bootstrapService(UserStore users, PasswordHasher hasher, AuditGate gate) {
            return new BootstrapService(users, hasher, gate);
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
        ApplyService applyService(InMemoryArtifactStore store) {
            return new ApplyService(CyntexCatalog.load(), store);
        }

        @Bean
        ArtifactQueryService artifactQueryService(InMemoryArtifactStore store) {
            return new ArtifactQueryService(store);
        }

        // The connection-test controller comes in with the whole ControlHttpFace bundle, so its service must
        // be present for the context to stand up. This suite exercises the auth matrix, not the probe, so the
        // service only needs to construct — its probe and result store are inert.
        @Bean
        ConnectionTestService connectionTestService(AuditGate auditGate) {
            ConnectionProbe probe = config -> {
                throw new UnsupportedOperationException("connection.test is not exercised in this test");
            };
            ConnectionTestResultStore resultStore = new ConnectionTestResultStore() {
                @Override
                public void save(ConnectionTestResult result) {
                }

                @Override
                public Optional<ConnectionTestResult> find(String connectionId) {
                    return Optional.empty();
                }
            };
            return new ConnectionTestService(probe, resultStore, auditGate);
        }

        // The read-back controller is bundled too, so its query service must be present for the context to
        // stand up; this suite exercises the auth matrix, not the read, so the store is inert (empty).
        @Bean
        ConnectionTestResultQueryService connectionTestResultQueryService() {
            return new ConnectionTestResultQueryService(new ConnectionTestResultStore() {
                @Override
                public void save(ConnectionTestResult result) {
                }

                @Override
                public Optional<ConnectionTestResult> find(String connectionId) {
                    return Optional.empty();
                }
            });
        }
    }

    // ---- fakes ----

    /** An in-memory user store keyed by username. */
    private static final class FakeUserStore implements UserStore {
        private final Map<String, User> users = new LinkedHashMap<>();

        void clear() {
            users.clear();
        }

        @Override
        public Optional<User> find(String username) {
            return Optional.ofNullable(users.get(username));
        }

        @Override
        public void save(User user) {
            users.put(user.username(), user);
        }

        @Override
        public boolean isEmpty() {
            return users.isEmpty();
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

    /** A deterministic password hasher (hash:raw). */
    private static final class FakeHasher implements PasswordHasher {
        @Override
        public String hash(String raw) {
            return "hash:" + raw;
        }

        @Override
        public boolean matches(String raw, String storedHash) {
            return storedHash.equals(hash(raw));
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

    /** A signer whose token is a reversible {@code subject|SCOPE} encoding, so a session token round-trips. */
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

    /**
     * An in-memory {@link io.cyntex.spi.store.ArtifactStore} holding each artifact as its canonical text,
     * mirroring the real store's write-then-parse round-trip so the apply / list verbs behave as in production.
     */
    private static final class InMemoryArtifactStore implements io.cyntex.spi.store.ArtifactStore {
        private final CanonicalWriter writer = new CanonicalWriter();
        private final DslParser parser = new DslParser();
        private final Map<String, String> byId = new LinkedHashMap<>();

        void clear() {
            byId.clear();
        }

        @Override
        public void saveAll(List<Resource> artifacts) {
            Map<String, String> staged = new LinkedHashMap<>();
            for (Resource artifact : artifacts) {
                staged.put(artifact.id(), writer.write(artifact));
            }
            byId.putAll(staged);
        }

        @Override
        public Optional<Resource> get(String id) {
            String canonical = byId.get(id);
            return canonical == null ? Optional.empty() : Optional.of(parser.parse(canonical));
        }

        @Override
        public List<Resource> list() {
            List<Resource> resources = new ArrayList<>();
            for (String canonical : byId.values()) {
                resources.add(parser.parse(canonical));
            }
            return resources;
        }
    }
}
