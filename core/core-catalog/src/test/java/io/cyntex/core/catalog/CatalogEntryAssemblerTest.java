package io.cyntex.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.cyntex.core.model.SourceMode;
import io.cyntex.core.model.WriteMode;

class CatalogEntryAssemblerTest {

    private static final Set<String> DB_CAPS =
            Set.of("batch_read_function", "stream_read_function", "write_record_function");

    @Test
    void databaseConnectorDerivesCdcSnapshotAndAnUpsertSink() {
        NormalizedSpec spec = spec("mysql", ConnectorGroup.DATABASE,
                List.of("update_on_exists", "ignore_on_exists", "just_insert"), true, null);

        ConnectorCatalogEntry entry =
                CatalogEntryAssembler.assemble(spec, DB_CAPS, "20371556", "mysql/mysql-spec.json", "h1");

        assertThat(entry.modes()).containsExactly(SourceMode.CDC, SourceMode.SNAPSHOT);
        assertThat(entry.group()).isEqualTo(ConnectorGroup.DATABASE);
        assertThat(entry.discovery()).isEqualTo(Discovery.CATALOG);
        assertThat(entry.sink().capable()).isTrue();
        assertThat(entry.sink().writeSemantics()).containsExactly(WriteMode.UPSERT, WriteMode.APPEND);
        assertThat(entry.pushOut()).isFalse();
        assertThat(entry.provenance().modeSource())
                .containsEntry(SourceMode.CDC, ModeSource.DERIVED)
                .containsEntry(SourceMode.SNAPSHOT, ModeSource.DERIVED);
    }

    @Test
    void declaredStreamModeReplacesTheDerivedDefaultAndClassifiesAsMq() {
        // Kafka mis-tags itself Database and registers stream read; the declared modes win, and the
        // stream mode plus the name push it to the MQ group with push-out enabled.
        NormalizedSpec spec = spec("kafka", ConnectorGroup.DATABASE, List.of(), false, List.of("stream"));

        ConnectorCatalogEntry entry = CatalogEntryAssembler.assemble(
                spec, Set.of("stream_read_function", "write_record_function"), "20371556", "kafka.json", "h2");

        assertThat(entry.modes()).containsExactly(SourceMode.STREAM);
        assertThat(entry.group()).isEqualTo(ConnectorGroup.MQ);
        assertThat(entry.pushOut()).isTrue();
        assertThat(entry.sink().capable()).isTrue();
        assertThat(entry.provenance().modeSource()).containsEntry(SourceMode.STREAM, ModeSource.DECLARED);
    }

    @Test
    void fileConnectorHasNoCatalogAndNoSinkWhenWriteIsAbsent() {
        NormalizedSpec spec = spec("csv", ConnectorGroup.FILE, List.of(), false, List.of("file"));

        ConnectorCatalogEntry entry = CatalogEntryAssembler.assemble(
                spec, Set.of("batch_read_function"), "20371556", "csv.json", "h3");

        assertThat(entry.modes()).containsExactly(SourceMode.FILE);
        assertThat(entry.group()).isEqualTo(ConnectorGroup.FILE);
        assertThat(entry.discovery()).isEqualTo(Discovery.NONE);
        assertThat(entry.sink().capable()).isFalse();
        assertThat(entry.sink().writeSemantics()).isEmpty();
    }

    @Test
    void apiConnectorClassifiesAsSaas() {
        NormalizedSpec spec = spec("github", ConnectorGroup.OTHER, List.of(), false, List.of("api"));

        ConnectorCatalogEntry entry =
                CatalogEntryAssembler.assemble(spec, Set.of(), "20371556", "github.json", "h4");

        assertThat(entry.modes()).containsExactly(SourceMode.API);
        assertThat(entry.group()).isEqualTo(ConnectorGroup.SAAS);
        assertThat(entry.sink().capable()).isFalse();
    }

    @Test
    void carriesIdentityConfigAndProvenanceStamp() {
        ConfigField host = new ConfigField("host", ConfigType.STRING, java.util.Map.of("en_US", "Host"),
                true, null, false, List.of(), null);
        NormalizedSpec spec = new NormalizedSpec("mysql", "Mysql", "MySQL", "icons/mysql.png",
                ConnectorGroup.DATABASE, List.of(host), List.of(), false, null);

        ConnectorCatalogEntry entry =
                CatalogEntryAssembler.assemble(spec, DB_CAPS, "20371556", "mysql/mysql-spec.json", "hash-abc");

        assertThat(entry.id()).isEqualTo("mysql");
        assertThat(entry.displayName()).isEqualTo("MySQL");
        assertThat(entry.icon()).isEqualTo("icons/mysql.png");
        assertThat(entry.config()).extracting(ConfigField::name).containsExactly("host");
        assertThat(entry.provenance().connectorRepoSha()).isEqualTo("20371556");
        assertThat(entry.provenance().specPath()).isEqualTo("mysql/mysql-spec.json");
        assertThat(entry.provenance().specContentHash()).isEqualTo("hash-abc");
    }

    @Test
    void rejectsAnUnknownDeclaredMode() {
        NormalizedSpec spec = spec("weird", ConnectorGroup.OTHER, List.of(), false, List.of("teleport"));

        assertThatThrownBy(() -> CatalogEntryAssembler.assemble(spec, Set.of(), "sha", "p", "h"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static NormalizedSpec spec(String id, ConnectorGroup tagGroup, List<String> dmlInsert,
                                       boolean hasUpdate, List<String> declaredModes) {
        return new NormalizedSpec(id, id, id, null, tagGroup, List.of(), dmlInsert, hasUpdate, declaredModes);
    }
}
