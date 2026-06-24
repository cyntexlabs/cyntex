package io.cyntex.core.catalog;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the real bundled catalog the build tool generated into main resources — the artifact the
 * runtime ships. This runs in every build (no connectors checkout, no PDK), so it is the always-on
 * guard that the checked-in catalog is internally consistent: the index and the per-connector entries
 * agree, every entry reconstructs, and the well-known connectors are present. A broken or empty
 * regeneration fails here. Assertions are by floor and by known id, not by exact count, so adding a
 * connector does not break this test.
 */
class CatalogConsistencyTest {

    private final CyntexCatalog catalog = CyntexCatalog.load();

    @Test
    void loadsAndKeepsTheIndexAndEntriesInStep() {
        // load() rejects a duplicate index id; this confirms every index id has exactly one entry.
        assertThat(catalog.ids()).doesNotHaveDuplicates();
        assertThat(catalog.all()).hasSameSizeAs(catalog.ids());
    }

    @Test
    void carriesTheWholeOfficialConnectorSet() {
        // A floor with headroom, not an exact count, so adding/removing a few connectors does not break
        // this — but a gross truncation (the real failure this guards) drops well below it. The exact
        // empty-modes-still-shipped guarantee is locked deterministically by the assembler unit tests
        // (a not-derived connector is emitted as an entry) and by the byte-lock in the refresh job.
        assertThat(catalog.ids()).hasSizeGreaterThan(70);
        assertThat(catalog.ids()).contains("mysql", "kafka", "mongodb", "doris");
    }

    @Test
    void reconstructsEveryEntryWithItsId() {
        for (String id : catalog.ids()) {
            ConnectorCatalogEntry entry = catalog.byId(id);
            assertThat(entry.id()).isEqualTo(id);
            assertThat(entry.group()).isNotNull();
            assertThat(entry.provenance().connectorRepoSha()).isNotBlank();
        }
    }

    @Test
    void derivesModesForADatabaseConnector() {
        ConnectorCatalogEntry mysql = catalog.byId("mysql");
        assertThat(mysql.group()).isEqualTo(ConnectorGroup.DATABASE);
        assertThat(mysql.modes()).contains(io.cyntex.core.model.SourceMode.SNAPSHOT,
                io.cyntex.core.model.SourceMode.CDC);
        assertThat(mysql.sink().capable()).isTrue();
    }

    @Test
    void everyIndexedIdIsResolvable() {
        List<String> ids = catalog.ids();
        assertThat(ids).allSatisfy(id -> assertThat(catalog.byId(id)).isNotNull());
    }
}
