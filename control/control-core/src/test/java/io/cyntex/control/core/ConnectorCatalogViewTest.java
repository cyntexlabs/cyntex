package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.cyntex.core.catalog.CatalogEntryReader;
import io.cyntex.core.catalog.CyntexCatalog;

/**
 * The online catalog view unions the bundled snapshot with the rows derived for registered connectors:
 * a registered connector becomes visible without a restart (the view re-reads the store per call), and
 * every listed connector is tagged bundled or registered so a face can tell an authored-in connector
 * from a user-uploaded one.
 */
class ConnectorCatalogViewTest {

    private static final CyntexCatalog BUNDLED = CyntexCatalog.load();

    private static final String ACME_ROW = """
            {
              "id": "acme", "name": "Acme", "displayName": "Acme", "icon": null,
              "group": "database", "modes": ["snapshot"], "discovery": "catalog",
              "sink": {"capable": false, "writeSemantics": []}, "pushOut": false, "config": [],
              "provenance": {"connectorRepoSha": null, "specPath": "spec.json", "specContentHash": "h",
                "pdkApiVersion": "1.0.0", "requiredLevel": null, "modeSource": {"snapshot": "derived"}}
            }
            """;

    @Test
    void mergedUnionsRegisteredRowsOverTheBundledSnapshot() {
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        store.upsert(CatalogEntryReader.read(ACME_ROW));
        ConnectorCatalogView view = new ConnectorCatalogView(BUNDLED, store);

        CyntexCatalog merged = view.merged();

        assertThat(merged.ids()).containsAll(BUNDLED.ids()).contains("acme");
        assertThat(merged.byId("acme").displayName()).isEqualTo("Acme");
    }

    @Test
    void mergedReflectsRegistrationsMadeAfterTheViewWasConstructed() {
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        ConnectorCatalogView view = new ConnectorCatalogView(BUNDLED, store);
        assertThat(view.merged().ids()).doesNotContain("acme");

        store.upsert(CatalogEntryReader.read(ACME_ROW));

        // The view re-reads the store per call, so a runtime registration shows up without a restart.
        assertThat(view.merged().ids()).contains("acme");
    }

    @Test
    void summariesTagRegisteredRowsRegisteredAndBundledRowsBundled() {
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        store.upsert(CatalogEntryReader.read(ACME_ROW));
        ConnectorCatalogView view = new ConnectorCatalogView(BUNDLED, store);

        List<ConnectorSummary> summaries = view.summaries();

        ConnectorSummary acme = summaries.stream().filter(s -> s.id().equals("acme")).findFirst().orElseThrow();
        assertThat(acme.origin()).isEqualTo("registered");
        assertThat(acme.modes()).contains("snapshot");
        String bundledId = BUNDLED.ids().get(0);
        ConnectorSummary bundled = summaries.stream().filter(s -> s.id().equals(bundledId)).findFirst().orElseThrow();
        assertThat(bundled.origin()).isEqualTo("bundled");
    }
}
