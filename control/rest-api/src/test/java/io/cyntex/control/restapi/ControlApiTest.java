package io.cyntex.control.restapi;

import io.cyntex.control.core.ApplyResult;
import io.cyntex.control.core.ApplyService;
import io.cyntex.control.core.ArtifactOutcome;
import io.cyntex.control.core.ArtifactQueryService;
import io.cyntex.control.core.ControlOperations;
import io.cyntex.control.core.Frontend;
import io.cyntex.control.core.Maturity;
import io.cyntex.control.core.Operation;
import io.cyntex.control.core.StoredArtifact;
import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.spi.store.ArtifactStore;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP face over the control verbs: each endpoint is a thin projection of a registered operation
 * onto {@code POST/GET /api/...}, and the endpoint table is a derivation of the registry — no endpoint
 * invents a verb. The apply / get / list verbs round-trip through the (fake-store-backed) control-core
 * services; the connection-test verb is routed but reserved. The context is booted programmatically so
 * the module stays on the reactor's JUnit line.
 */
class ControlApiTest {

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
        ((InMemoryArtifactStore) context.getBean(ArtifactStore.class)).clear();
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private ApplyResult applyDrafts(String... drafts) {
        List<Map<String, String>> body = new ArrayList<>();
        for (String draft : drafts) {
            body.add(Map.of("content", draft));
        }
        return client().post().uri("/api/artifacts:apply")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("drafts", body))
                .retrieve().toEntity(ApplyResult.class).getBody();
    }

    // ---- the verbs project onto HTTP ----

    @Test
    void applyUpsertsAndReturnsTheOutcomes() {
        ApplyResult result = applyDrafts(TGT_MY);

        assertThat(result.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.id()).isEqualTo("tgt_my");
            assertThat(o.kind()).isEqualTo("source");
            assertThat(o.change()).isEqualTo(ArtifactOutcome.Change.CREATED);
            assertThat(o.contentHash()).matches("[0-9a-f]{64}");
        });
    }

    @Test
    void getReadsBackTheAppliedArtifactAsItsCanonicalForm() {
        applyDrafts(TGT_MY);

        ResponseEntity<StoredArtifact> got = client().get().uri("/api/artifacts/tgt_my")
                .retrieve().toEntity(StoredArtifact.class);

        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(got.getBody().id()).isEqualTo("tgt_my");
        assertThat(got.getBody().canonicalForm()).isEqualTo(offlineCanonical(TGT_MY));
    }

    @Test
    void getAnUnknownIdIsNotFound() {
        HttpStatusCode status = client().get().uri("/api/artifacts/no_such_id")
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listReturnsEveryStoredArtifact() {
        applyDrafts(SRC_ORA, TGT_MY, PIPELINE);

        ArtifactList listed = client().get().uri("/api/artifacts")
                .retrieve().toEntity(ArtifactList.class).getBody();

        assertThat(listed.artifacts()).extracting(StoredArtifact::id)
                .containsExactlyInAnyOrder("src_ora", "tgt_my", "ora2my_ods");
    }

    @Test
    void listByKindFiltersToThatKind() {
        applyDrafts(SRC_ORA, TGT_MY, PIPELINE);

        ArtifactList sources = client().get().uri("/api/artifacts?kind=source")
                .retrieve().toEntity(ArtifactList.class).getBody();

        assertThat(sources.artifacts()).extracting(StoredArtifact::id)
                .containsExactlyInAnyOrder("src_ora", "tgt_my");
    }

    // ---- coded errors project onto structured HTTP responses ----

    @Test
    void applyingAnInvalidDraftIsABadRequestWithACodedBody() {
        // A validation failure (an unknown field) surfaces the dsl.* coded diagnostic before any store
        // write; the advice projects it onto a 400 with the {code, params, message} body — no 500.
        ApiError body = client().post().uri("/api/artifacts:apply")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("drafts", List.of(Map.of("content", UNKNOWN_FIELD_DRAFT))))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    return response.bodyTo(ApiError.class);
                });

        assertThat(body.code()).isEqualTo("dsl.unknown-field");
        assertThat(body.message()).isEqualTo("Unknown field 'snapshot_mode' at options.snapshot_mode.");
        assertThat(body.params()).containsEntry("field", "snapshot_mode");
    }

    @Test
    void anUncodedFailureStaysABareServerErrorNotACodedBody() {
        // A request with a null drafts field currently trips an invariant guard inside the service (a bare
        // NPE). Whatever its origin, an uncoded throwable must never be laundered into a coded body: the
        // advice catches only CyntexException, so this stays a bare 500 with no {code} envelope. (Coercing a
        // malformed request into a coded 4xx is a separate request-validation concern, not this rule.)
        Map<String, Object> body = client().post().uri("/api/artifacts:apply")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().is5xxServerError()).isTrue();
                    return response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {});
                });

        assertThat(body).doesNotContainKey("code");
    }

    @Test
    void connectionTestIsRoutedButNotYetImplemented() {
        // The R5 synchronous verb is reserved and routed; its probe and result-store land later, so it
        // answers 501 rather than fabricating a result.
        HttpStatusCode status = client().post().uri("/api/connections:test")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of())
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    // ---- the anonymous probe lives outside the verb surface ----

    @Test
    void healthzAnswersAtTheRootOutsideTheApiPrefix() {
        // The load-balancer probe is pure HTTP: anonymous, at the root, not a registry verb. It must
        // not be swept under the /api prefix — that root is exactly what the plain-@Controller carve-out
        // in the path-prefix rule exists for.
        ResponseEntity<String> probe = client().get().uri("/healthz").retrieve().toEntity(String.class);

        assertThat(probe.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(probe.getBody()).isEqualTo("ok");

        HttpStatusCode underApi = client().get().uri("/api/healthz")
                .exchange((request, response) -> response.getStatusCode());
        assertThat(underApi).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void clusterMembersIsRoutedButNotYetImplemented() {
        // Topology must never leak anonymously: until the authentication interceptor and the member
        // listing land, the endpoint is reserved and answers 501 — it exposes nothing.
        HttpStatusCode status = client().get().uri("/api/cluster/members")
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    // ---- the endpoint table is a derivation of the registry ----

    @Test
    void everyApiEndpointProjectsARegisteredCliExposedVerb() {
        Set<String> cliExposed = ControlOperations.registry()
                .exposedOn(Frontend.CLI, Maturity.POC).stream()
                .map(Operation::id).collect(Collectors.toSet());

        RequestMappingHandlerMapping mapping =
                context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);

        List<String> handlersMissingVerb = new ArrayList<>();
        List<String> verbsNotDerived = new ArrayList<>();
        List<String> projected = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            boolean underApi = info.getPathPatternsCondition() != null
                    && info.getPathPatternsCondition().getPatternValues().stream()
                            .anyMatch(p -> p.startsWith("/api"));
            if (!underApi) {
                return;
            }
            Verb verb = handler.getMethodAnnotation(Verb.class);
            if (verb == null) {
                handlersMissingVerb.add(describe(handler));
            } else {
                projected.add(verb.value());
                if (!cliExposed.contains(verb.value())) {
                    verbsNotDerived.add(verb.value());
                }
            }
        });

        assertThat(handlersMissingVerb)
                .as("every /api endpoint must project a control verb (carry @Verb) — a face composes "
                        + "registered operations, it never invents an endpoint")
                .isEmpty();
        assertThat(verbsNotDerived)
                .as("every projected verb must be a registered, CLI-exposed operation")
                .isEmpty();
        assertThat(projected)
                .as("the artifact verbs, the R5 connection-test verb and the topology verb are projected onto HTTP")
                .contains("artifact.apply", "artifact.get", "artifact.list", "connection.test", "cluster.members");
    }

    private static String describe(HandlerMethod handler) {
        return handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
    }

    /** The offline canonical contract for a draft: the exact bytes the authoring corpus golden locks. */
    private static String offlineCanonical(String draft) {
        return new CanonicalWriter().write(new DslParser().parse(draft));
    }

    /**
     * A minimal boot config: auto-configures Web MVC + the embedded servlet container, imports the path
     * prefix configuration and the verb controllers, and constructs the control-core services over an
     * in-memory artifact store (the store-backed wiring lands at the assembly root, not here).
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({RestApiConfiguration.class, ArtifactController.class, ConnectionController.class,
            ClusterController.class, HealthController.class, ApiExceptionHandler.class})
    static class TestApp {

        @Bean
        ArtifactStore artifactStore() {
            return new InMemoryArtifactStore();
        }

        @Bean
        ApplyService applyService(ArtifactStore store) {
            return new ApplyService(CyntexCatalog.load(), store);
        }

        @Bean
        ArtifactQueryService artifactQueryService(ArtifactStore store) {
            return new ArtifactQueryService(store);
        }
    }

    // ---- fixtures ----

    /** A source draft carrying a field outside the cyntex/v1 schema — rejected as dsl.unknown-field. */
    private static final String UNKNOWN_FIELD_DRAFT = """
            version: cyntex/v1
            kind: source
            id: src_ora
            connector: oracle
            config: { host: 10.20.0.15 }
            mode: cdc
            tables: [ ORDERS ]
            options: { snapshot_mode: initial, include_ddl: true }
            """;

    private static final String TGT_MY = """
            version: cyntex/v1
            kind: source
            id: tgt_my
            connector: mysql
            config: { host: 10.30.0.5, username: writer, password: My_2026 }
            """;

    private static final String SRC_ORA = """
            version: cyntex/v1
            kind: source
            id: src_ora
            connector: oracle
            config: { host: 10.20.0.15, port: 1521, service_name: ORCL,
                      username: cdc_user, password: Ora_2026 }
            mode: cdc
            tables: [ ORDERS, ORDER_ITEMS, CUSTOMERS ]
            options: { include_ddl: true }
            """;

    private static final String PIPELINE = """
            version: cyntex/v1
            kind: pipeline
            id: ora2my_ods
            source: src_ora
            settings: { read_mode: snapshot_and_cdc }
            serve:
              from: /.*/
              sync:
                - id: my_ods
                  source: tgt_my
                  write_mode: upsert
                  ddl: apply
            """;

    /**
     * An in-memory {@link ArtifactStore} that mirrors the Mongo store's canonical round-trip — it holds
     * each artifact as its canonical text and reconstructs it on read through the parser — so a read
     * exercises the same write-then-parse the real store does. Clearable so each test seeds a clean store.
     */
    private static final class InMemoryArtifactStore implements ArtifactStore {

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
