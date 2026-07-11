package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexException;
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
 * The source-model document codec is the mapping core of the schema store: a discovered source model is
 * stored as a structured document keyed by the connection id — its tables, and each table's fields,
 * primary key and indexes, as nested sub-documents — and reconstructed from it on read. These witness
 * the mapping deterministically, without a Mongo server: the document shape, a full round-trip
 * (including a field with no resolved type and both a unique and a non-unique index), an empty model,
 * and that a structurally corrupt stored document surfaces as a coded {@code io.document-unreadable}
 * diagnostic rather than a bare crash. A real Mongo round-trip is exercised by
 * {@code MongoSchemaStoreIT} (skipped where Docker is absent).
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

    @Test
    void documentCarriesIdAndTables() {
        Document document = MongoSchemaStore.toDocument("orders-db", ordersModel());

        assertThat(document.getString("_id")).isEqualTo("orders-db");
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
    void roundTripReconstructsTheSameModel() {
        SourceModel model = ordersModel();

        assertThat(MongoSchemaStore.toModel(MongoSchemaStore.toDocument("orders-db", model))).isEqualTo(model);
    }

    @Test
    void emptyModelRoundTrips() {
        SourceModel model = new SourceModel(List.of());

        assertThat(MongoSchemaStore.toModel(MongoSchemaStore.toDocument("bare", model))).isEqualTo(model);
    }

    @Test
    void aFieldWithNoResolvedTypeRoundTripsAsNull() {
        SourceModel model = new SourceModel(List.of(
                new SourceTable("t", List.of(new SourceField("c", null)), List.of(), List.of())));

        SourceModel read = MongoSchemaStore.toModel(MongoSchemaStore.toDocument("x", model));

        assertThat(read.tables().get(0).fields().get(0).type()).isNull();
        assertThat(read).isEqualTo(model);
    }

    @Test
    void toModelWithAnAbsentTablesFieldReadsBackEmpty() {
        SourceModel read = MongoSchemaStore.toModel(new Document("_id", "bare"));

        assertThat(read.tables()).isEmpty();
    }

    @Test
    void toModelOnATableMissingItsNameIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders-db")
                .append("tables", List.of(new Document("fields", List.of())));

        Throwable thrown = catchThrowable(() -> MongoSchemaStore.toModel(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "orders-db");
    }

    @Test
    void toModelOnATablesFieldThatIsNotAListIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders-db").append("tables", "oops");

        Throwable thrown = catchThrowable(() -> MongoSchemaStore.toModel(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }

    @Test
    void toModelOnAnIndexMissingItsNameIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders-db").append("tables", List.of(
                new Document("name", "orders").append("indexes", List.of(new Document("unique", true)))));

        Throwable thrown = catchThrowable(() -> MongoSchemaStore.toModel(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }
}
