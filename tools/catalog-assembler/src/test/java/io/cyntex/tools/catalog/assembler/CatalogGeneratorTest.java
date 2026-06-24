package io.cyntex.tools.catalog.assembler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.cyntex.core.catalog.CatalogEntryReader;
import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.catalog.ConnectorGroup;
import io.cyntex.core.model.SourceMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end: from a connectors checkout and a derived bitmap, the generator walks, normalizes,
 * merges and serializes to a deterministic index, per-connector entries that read back, and an
 * ingest report — the whole PDK-free assembly path, exercised without a real checkout or PDK.
 */
class CatalogGeneratorTest {

    @TempDir
    Path repo;

    @BeforeEach
    void buildFixture() throws IOException {
        write("connectors/mysql-connector/src/main/java/io/tapdata/connector/mysql/MysqlConnector.java", """
                package io.tapdata.connector.mysql;
                @TapConnectorClass("mysql-spec.json")
                public class MysqlConnector {}
                """);
        write("connectors/mysql-connector/src/main/resources/mysql-spec.json", """
                {"properties":{"id":"mysql","name":"Mysql","realName":"MySQL","icon":"icons/mysql.png",
                  "tags":["Database"]},
                 "configOptions":{"capabilities":[
                    {"id":"dml_insert_policy","alternatives":["update_on_exists"]},
                    {"id":"dml_update_policy"}],
                   "connection":{"properties":{"host":{"type":"string","title":"Host","required":true}}}},
                 "messages":{"default":"en_US","en_US":{}}}
                """);
        write("connectors-javascript/github-connector/src/main/resources/spec.json", """
                {"properties":{"id":"github","name":"GitHub","icon":"icon/github.png","tags":["SaaS"]},
                 "configOptions":{"connection":{"properties":{}}},
                 "messages":{"default":"en_US","en_US":{}}}
                """);
    }

    @Test
    void generatesADeterministicIndexInIdOrder() {
        assertThat(generate().index()).isEqualTo("""
                [
                  "github",
                  "mysql"
                ]
                """);
    }

    @Test
    void generatesEntriesThatReadBackWithDerivedModes() {
        ConnectorCatalogEntry mysql = CatalogEntryReader.read(generate().entries().get("mysql"));
        assertThat(mysql.modes()).containsExactlyInAnyOrder(SourceMode.SNAPSHOT, SourceMode.CDC);
        assertThat(mysql.group()).isEqualTo(ConnectorGroup.DATABASE);
        assertThat(mysql.provenance().connectorRepoSha()).isEqualTo("testsha");
    }

    @Test
    void reportsTheUndeclaredJavaScriptConnectorAsUnclassified() {
        assertThat(generate().report())
                .contains("## Unclassified — no resolvable mode (need cyntex.modes)")
                .contains("- github");
    }

    @Test
    void failsLoudWhenTwoIdsCollideCaseInsensitivelyBecauseEntryFilenamesAreIdDerived() throws IOException {
        // <id>.json filenames collapse on a case-insensitive filesystem (the documented dev platform),
        // which would silently overwrite one entry. Catch the collision at generation, not at write.
        write("connectors-javascript/dup-connector/src/main/resources/spec.json", """
                {"properties":{"id":"dup","name":"Dup","icon":"i.png","tags":["SaaS"]},
                 "configOptions":{"connection":{"properties":{}}},
                 "messages":{"default":"en_US","en_US":{}}}
                """);
        write("connectors-javascript/dup2-connector/src/main/resources/spec.json", """
                {"properties":{"id":"Dup","name":"Dup2","icon":"i.png","tags":["SaaS"]},
                 "configOptions":{"connection":{"properties":{}}},
                 "messages":{"default":"en_US","en_US":{}}}
                """);

        assertThatThrownBy(() -> CatalogGenerator.generate(repo, "testsha", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    private GeneratedCatalog generate() {
        Map<String, Set<String>> bitmap = Map.of("mysql",
                Set.of("batch_read_function", "stream_read_function", "write_record_function"));
        return CatalogGenerator.generate(repo, "testsha", bitmap);
    }

    private void write(String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
