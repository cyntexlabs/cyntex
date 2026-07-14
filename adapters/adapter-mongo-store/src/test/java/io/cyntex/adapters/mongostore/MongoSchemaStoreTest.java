package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.DiscoveredSourceModel;
import io.cyntex.spi.store.SourceField;
import io.cyntex.spi.store.SourceIndex;
import io.cyntex.spi.store.SourceModel;
import io.cyntex.spi.store.SourceTable;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The discovered-schema document codec is the mapping core of the schema store: a discovery envelope —
 * the connection id as the key, the connector id and discovery time it reports, and the source model's
 * tables (with their fields, primary key and indexes) as nested sub-documents — is stored as one
 * structured document and reconstructed from it on read. These witness the mapping deterministically,
 * without a Mongo server: the document shape, a full round-trip (including a field with no resolved
 * type and both a unique and a non-unique index), an empty model, and that a structurally corrupt
 * stored document surfaces as a coded {@code io.document-unreadable} diagnostic rather than a bare
 * crash. A real Mongo round-trip is exercised by {@code MongoSchemaStoreIT} (skipped where Docker is
 * absent).
 */
class MongoSchemaStoreTest {

    private static SourceModel ordersModel() {
        SourceTable orders = new SourceTable(
                "orders",
                List.of(new SourceField("id", "bigint"), new SourceField("note", null)),
                List.of("id"),
                List.of(
                        new SourceIndex("pk_orders", List.of("id"), true),
                        new SourceIndex("by_note", List.of("note"), false)));
        SourceTable customers = new SourceTable(
                "customers",
                List.of(new SourceField("email", "varchar")),
                List.of("email"),
                List.of());
        return new SourceModel(List.of(orders, customers));
    }

    private static DiscoveredSourceModel discovered(String connectionId, SourceModel model) {
        return new DiscoveredSourceModel(connectionId, "mysql", 1783998000000L, model);
    }

    @Test
    void documentCarriesIdConnectorIdDiscoveredAtAndTables() {
        Document document = MongoSchemaStore.toDocument(discovered("orders-db", ordersModel()));

        assertThat(document.getString("_id")).isEqualTo("orders-db");
        assertThat(document.getString("connectorId")).isEqualTo("mysql");
        assertThat(document.getLong("discoveredAt")).isEqualTo(1783998000000L);
        List<Document> tables = document.getList("tables", Document.class);
        assertThat(tables).extracting(t -> t.getString("name")).containsExactly("orders", "customers");

        Document orders = tables.get(0);
        assertThat(orders.getList("primaryKey", String.class)).containsExactly("id");
        assertThat(orders.getList("fields", Document.class))
                .extracting(f -> f.getString("name"))
                .containsExactly("id", "note");
        assertThat(orders.getList("indexes", Document.class))
                .extracting(i -> i.getString("name"), i -> i.getBoolean("unique"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("pk_orders", true),
                        org.assertj.core.groups.Tuple.tuple("by_note", false));
    }

    @Test
    void roundTripReconstructsTheSameEnvelope() {
        DiscoveredSourceModel envelope = discovered("orders-db", ordersModel());

        assertThat(MongoSchemaStore.toDiscovered(MongoSchemaStore.toDocument(envelope))).isEqualTo(envelope);
    }

    @Test
    void emptyModelRoundTrips() {
        DiscoveredSourceModel envelope = discovered("bare", new SourceModel(List.of()));

        assertThat(MongoSchemaStore.toDiscovered(MongoSchemaStore.toDocument(envelope))).isEqualTo(envelope);
    }

    @Test
    void aFieldWithNoResolvedTypeRoundTripsAsNull() {
        DiscoveredSourceModel envelope = discovered("x", new SourceModel(List.of(
                new SourceTable("t", List.of(new SourceField("c", null)), List.of(), List.of()))));

        DiscoveredSourceModel read = MongoSchemaStore.toDiscovered(MongoSchemaStore.toDocument(envelope));

        assertThat(read.model().tables().get(0).fields().get(0).type()).isNull();
        assertThat(read).isEqualTo(envelope);
    }

    @Test
    void toDiscoveredWithAnAbsentTablesFieldReadsBackEmpty() {
        DiscoveredSourceModel read = MongoSchemaStore.toDiscovered(
                new Document("_id", "bare").append("connectorId", "mysql").append("discoveredAt", 1L));

        assertThat(read.model().tables()).isEmpty();
    }

    @Test
    void toDiscoveredOnADocumentMissingItsConnectorIdIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders-db").append("discoveredAt", 1L);

        Throwable thrown = catchThrowable(() -> MongoSchemaStore.toDiscovered(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "orders-db");
    }

    @Test
    void toDiscoveredOnADocumentWithoutANumericDiscoveredAtIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders-db").append("connectorId", "mysql").append("discoveredAt", "oops");

        Throwable thrown = catchThrowable(() -> MongoSchemaStore.toDiscovered(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }

    @Test
    void toDiscoveredOnATableMissingItsNameIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders-db")
                .append("connectorId", "mysql")
                .append("discoveredAt", 1L)
                .append("tables", List.of(new Document("fields", List.of())));

        Throwable thrown = catchThrowable(() -> MongoSchemaStore.toDiscovered(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "orders-db");
    }

    @Test
    void toDiscoveredOnATablesFieldThatIsNotAListIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders-db")
                .append("connectorId", "mysql")
                .append("discoveredAt", 1L)
                .append("tables", "oops");

        Throwable thrown = catchThrowable(() -> MongoSchemaStore.toDiscovered(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }

    @Test
    void toDiscoveredOnAnIndexMissingItsNameIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders-db")
                .append("connectorId", "mysql")
                .append("discoveredAt", 1L)
                .append("tables", List.of(
                        new Document("name", "orders").append("indexes", List.of(new Document("unique", true)))));

        Throwable thrown = catchThrowable(() -> MongoSchemaStore.toDiscovered(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }
}
