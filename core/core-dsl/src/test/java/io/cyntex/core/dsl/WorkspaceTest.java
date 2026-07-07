package io.cyntex.core.dsl;

import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.model.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The in-memory full-semantic validation entry: {@link Workspace#of(List, CyntexCatalog)} runs the
 * batch layers (duplicate id, reference closure, mode rules) and the connector capability matrix
 * with no filesystem. It is the shared validation core the directory loader and the online apply
 * path both build on; the no-catalog {@link Workspace#of(List)} deliberately stops before the
 * capability tier (the offline-without-catalog scope).
 */
class WorkspaceTest {

    private final DslParser parser = new DslParser();
    private final CyntexCatalog catalog = CyntexCatalog.load();

    private Resource parse(String yaml) {
        return parser.parse(yaml);
    }

    // kafka declares only [stream]; cdc is outside its capability matrix — a catalog-tier rejection.
    private static final String KAFKA_CDC = """
            version: cyntex/v1
            kind: source
            id: src_k
            connector: kafka
            config: { nameSrvAddr: "k1:9092" }
            mode: cdc
            tables: [ events ]
            """;

    @Test
    void ofWithCatalogRunsTheCapabilityMatrix() {
        Throwable t = catchThrowable(() -> Workspace.of(List.of(parse(KAFKA_CDC)), catalog));

        assertThat(t).isInstanceOf(DslException.class);
        assertThat(((DslException) t).code()).isEqualTo(DslError.UNSUPPORTED_MODE);
    }

    @Test
    void ofWithoutCatalogStopsBeforeTheCapabilityMatrix() {
        // The one-arg form runs duplicate-id + reference closure + mode rules, but not the
        // connector capability matrix — so the same catalog-tier violation is not raised here.
        assertThatCode(() -> Workspace.of(List.of(parse(KAFKA_CDC))))
                .as("one-arg of() stops before the catalog capability tier")
                .doesNotThrowAnyException();
    }

    @Test
    void ofWithCatalogReturnsTheValidatedBatch() {
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_my
                connector: mysql
                config: { host: 10.0.0.1, username: u, password: p }
                mode: snapshot
                tables: [ orders ]
                """);

        Workspace ws = Workspace.of(List.of(src), catalog);

        assertThat(ws.resource("src_my")).isSameAs(src);
    }
}
