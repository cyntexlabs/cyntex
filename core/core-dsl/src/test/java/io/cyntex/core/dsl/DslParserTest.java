package io.cyntex.core.dsl;

import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceMode;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.TableRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Parse layer (plan poc1 B3): YAML text -> resource model. The inverse of the canonical
 * writer — parse accepts any legal YAML style (block / flow / any key order / sugar),
 * the model is the post-normalization form (canonical-form.md §1).
 */
class DslParserTest {

    private final DslParser parser = new DslParser();

    @Test
    void parsesMinimalSource() {
        String yaml = """
                version: cyntex/v1
                kind: source
                id: src_ora
                connector: oracle
                config: { host: 10.20.0.15, port: 1521, service_name: ORCL }
                mode: cdc
                tables: [ ORDERS, ORDER_ITEMS, CUSTOMERS ]
                options: { include_ddl: true, heartbeat_interval: 10s }
                """;

        Resource r = parser.parse(yaml);

        assertThat(r).isInstanceOf(SourceResource.class);
        SourceResource s = (SourceResource) r;
        assertThat(s.id()).isEqualTo("src_ora");
        assertThat(s.connector()).isEqualTo("oracle");
        assertThat(s.mode()).isEqualTo(SourceMode.CDC);
        assertThat(s.tables()).containsExactly(
                TableRef.literal("ORDERS"), TableRef.literal("ORDER_ITEMS"), TableRef.literal("CUSTOMERS"));
        assertThat(s.config())
                .containsEntry("host", "10.20.0.15")
                .containsEntry("port", 1521)
                .containsEntry("service_name", "ORCL");
        assertThat(s.options())
                .containsEntry("include_ddl", true)
                .containsEntry("heartbeat_interval", "10s");
    }

    @Test
    void rejectsRelocatedSourceReadOption() {
        // read_mode / start_from moved to pipeline settings; the old source-level option names are
        // rejected as unknown fields rather than silently passed through the free options map.
        String yaml = """
                version: cyntex/v1
                kind: source
                id: src_ora
                connector: oracle
                config: { host: 10.20.0.15 }
                mode: cdc
                tables: [ ORDERS ]
                options: { snapshot_mode: initial, include_ddl: true }
                """;

        Throwable t = catchThrowable(() -> parser.parse(yaml));

        assertThat(t).isInstanceOf(DslException.class);
        DslException ex = (DslException) t;
        assertThat(ex.code()).isEqualTo(DslError.UNKNOWN_FIELD);
        assertThat(ex.path()).isEqualTo("options.snapshot_mode");
        assertThat(ex.args()).containsEntry("field", "snapshot_mode");
    }

    @Test
    void rejectsRelocatedStartFromOption() {
        String yaml = """
                version: cyntex/v1
                kind: source
                id: src_kfk
                connector: kafka
                config: { brokers: k1:9092 }
                mode: stream
                tables: [ orders_topic ]
                options: { start_from: earliest }
                """;

        Throwable t = catchThrowable(() -> parser.parse(yaml));

        assertThat(t).isInstanceOf(DslException.class);
        DslException ex = (DslException) t;
        assertThat(ex.code()).isEqualTo(DslError.UNKNOWN_FIELD);
        assertThat(ex.path()).isEqualTo("options.start_from");
    }

    @Test
    void parsesSrsEnabledFalse() {
        // read amendment: srs.enabled: false is the SRS off switch (default true).
        String yaml = """
                version: cyntex/v1
                kind: source
                id: src
                connector: mysql
                mode: cdc
                tables: [ orders ]
                srs: { enabled: false }
                """;

        SourceResource s = (SourceResource) parser.parse(yaml);

        assertThat(s.srs().enabled()).isFalse();
    }

    @Test
    void rejectsUnknownTopLevelField() {
        // mirrors corpus invalid/s01: `mod` is a typo of `mode`; §11.5 rejects, never ignores
        String yaml = """
                version: cyntex/v1
                kind: source
                id: src_typo
                connector: mysql
                config: { host: 10.0.0.1 }
                mod: cdc
                tables: [ orders ]
                """;

        Throwable t = catchThrowable(() -> parser.parse(yaml));

        assertThat(t).isInstanceOf(DslException.class);
        DslException ex = (DslException) t;
        assertThat(ex.code()).isEqualTo(DslError.UNKNOWN_FIELD);
        assertThat(ex.path()).isEqualTo("mod");
        assertThat(ex.line()).isEqualTo(6);
    }

    @Test
    void rejectsUnknownKind() {
        // A top-level kind outside the closed set is a coded value error, not a bare crash.
        String yaml = """
                version: cyntex/v1
                kind: bogus
                id: x
                """;

        Throwable t = catchThrowable(() -> parser.parse(yaml));

        assertThat(t).isInstanceOf(DslException.class);
        DslException ex = (DslException) t;
        assertThat(ex.code()).isEqualTo(DslError.ILLEGAL_VALUE);
        assertThat(ex.path()).isEqualTo("kind");
        assertThat(ex.args()).containsEntry("value", "bogus");
    }

    @Test
    void rejectsMissingKind() {
        // No kind: at all is likewise a coded value error, not an UnsupportedOperationException.
        String yaml = """
                version: cyntex/v1
                id: x
                """;

        Throwable t = catchThrowable(() -> parser.parse(yaml));

        assertThat(t).isInstanceOf(DslException.class);
        DslException ex = (DslException) t;
        assertThat(ex.code()).isEqualTo(DslError.ILLEGAL_VALUE);
        assertThat(ex.path()).isEqualTo("kind");
    }
}
