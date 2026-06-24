package io.cyntex.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.cyntex.core.model.SourceMode;

/**
 * Unit-tests the loader assembly via {@link CyntexCatalog#build} with inline entries — index order is
 * preserved, an entry reconstructs fully, an unknown id is rejected, and a duplicated index id fails
 * loud rather than silently dropping an entry. The real bundled catalog is exercised separately by
 * {@link CatalogConsistencyTest}.
 */
class CyntexCatalogTest {

    private static final String MYSQL = """
            {
              "id": "mysql", "name": "Mysql", "displayName": "MySQL", "icon": null,
              "group": "database", "modes": ["snapshot"], "discovery": "catalog",
              "sink": {"capable": false, "writeSemantics": []},
              "pushOut": false, "config": [],
              "provenance": {"connectorRepoSha": "x", "specPath": "x", "specContentHash": "x",
                "pdkApiVersion": null, "requiredLevel": null, "modeSource": {"snapshot": "derived"}}
            }
            """;

    private static final String KAFKA = """
            {
              "id": "kafka", "name": "Kafka", "displayName": "Apache Kafka", "icon": null,
              "group": "mq", "modes": ["stream"], "discovery": "catalog",
              "sink": {"capable": true, "writeSemantics": ["upsert", "append"]},
              "pushOut": true, "config": [],
              "provenance": {"connectorRepoSha": "x", "specPath": "x", "specContentHash": "x",
                "pdkApiVersion": null, "requiredLevel": null, "modeSource": {"stream": "declared"}}
            }
            """;

    private static final Map<String, String> ENTRIES = Map.of("mysql", MYSQL, "kafka", KAFKA);

    @Test
    void preservesConnectorIdsInIndexOrder() {
        assertThat(CyntexCatalog.build(List.of("mysql", "kafka"), ENTRIES::get).ids())
                .containsExactly("mysql", "kafka");
    }

    @Test
    void reconstructsEveryEntryInIndexOrder() {
        assertThat(CyntexCatalog.build(List.of("mysql", "kafka"), ENTRIES::get).all())
                .extracting(ConnectorCatalogEntry::id).containsExactly("mysql", "kafka");
    }

    @Test
    void resolvesAnEntryByIdAndReconstructsItFully() {
        ConnectorCatalogEntry kafka = CyntexCatalog.build(List.of("kafka"), ENTRIES::get).byId("kafka");

        assertThat(kafka.group()).isEqualTo(ConnectorGroup.MQ);
        assertThat(kafka.modes()).containsExactly(SourceMode.STREAM);
        assertThat(kafka.pushOut()).isTrue();
        assertThat(kafka.provenance().modeSource()).containsEntry(SourceMode.STREAM, ModeSource.DECLARED);
    }

    @Test
    void rejectsAnUnknownConnectorId() {
        assertThatThrownBy(() -> CyntexCatalog.build(List.of("mysql"), ENTRIES::get).byId("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsADuplicateIdInTheIndex() {
        // A duplicated index id would desync ids()/all() and silently drop an entry — fail loud.
        assertThatThrownBy(() -> CyntexCatalog.build(List.of("mysql", "mysql"), ENTRIES::get))
                .isInstanceOf(IllegalStateException.class);
    }
}
