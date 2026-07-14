package io.cyntex.adapters.mongostore;

import io.cyntex.core.catalog.CatalogEntryReader;
import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.common.CyntexException;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The connector-catalog-row codec is the mapping core of the derived-row store: a normalized catalog
 * row is stored as one structured document keyed by the connector id and reconstructed from it on
 * read, reusing the catalog product's writer / reader so the persisted shape matches the bundled one.
 * These witness the mapping deterministically, without a Mongo server — a full-fidelity round-trip
 * (config, options, visibleWhen, provenance and sink included) and a coded diagnostic when a stored
 * row cannot be reconstructed. A real Mongo round-trip is exercised by
 * {@code MongoConnectorCatalogStoreIT} (skipped where Docker is absent).
 */
class MongoConnectorCatalogStoreTest {

    private static final String MYSQL_ROW = """
            {
              "id": "mysql",
              "name": "Mysql",
              "displayName": "MySQL",
              "icon": "icons/mysql.png",
              "group": "database",
              "modes": ["cdc", "snapshot"],
              "discovery": "catalog",
              "sink": {"capable": true, "writeSemantics": ["upsert", "append"]},
              "pushOut": false,
              "config": [
                {
                  "name": "deploymentMode",
                  "type": "string",
                  "label": {"en_US": "Deployment mode"},
                  "required": false,
                  "default": "standalone",
                  "secret": false,
                  "options": [
                    {"value": "standalone", "label": {"en_US": "Single machine deployment"}},
                    {"value": "master-slave", "label": {"en_US": "Master-Slave Architecture"}}
                  ],
                  "visibleWhen": null
                },
                {
                  "name": "host",
                  "type": "string",
                  "label": {"en_US": "Host"},
                  "required": true,
                  "default": null,
                  "secret": false,
                  "options": [],
                  "visibleWhen": {"controllingField": "deploymentMode", "equalsAnyOf": ["standalone"]}
                }
              ],
              "provenance": {
                "connectorRepoSha": "20371556",
                "specPath": "mysql-connector/src/main/resources/mysql-spec.json",
                "specContentHash": "abc123",
                "pdkApiVersion": "1.3.5",
                "requiredLevel": null,
                "modeSource": {"cdc": "derived", "snapshot": "derived"}
              }
            }
            """;

    @Test
    void documentIsKeyedByTheConnectorIdAndCarriesTheRow() {
        ConnectorCatalogEntry entry = CatalogEntryReader.read(MYSQL_ROW);

        Document document = MongoConnectorCatalogStore.toDocument(entry);

        assertThat(document.getString("_id")).isEqualTo("mysql");
        assertThat(document.getString("id")).isEqualTo("mysql");
        assertThat(document.getString("group")).isEqualTo("database");
    }

    @Test
    void roundTripReconstructsTheSameRowThroughStructuredBson() {
        ConnectorCatalogEntry entry = CatalogEntryReader.read(MYSQL_ROW);

        assertThat(MongoConnectorCatalogStore.toEntry(MongoConnectorCatalogStore.toDocument(entry)))
                .isEqualTo(entry);
    }

    @Test
    void toEntryOnAnUnreadableRowIsDocumentUnreadable() {
        // A stored row missing its required id cannot be reconstructed — surfaced as a coded io
        // diagnostic rather than a bare crash while reconstructing.
        Document corrupt = new Document("_id", "mysql").append("name", "Mysql");

        Throwable thrown = catchThrowable(() -> MongoConnectorCatalogStore.toEntry(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "mysql");
    }
}
