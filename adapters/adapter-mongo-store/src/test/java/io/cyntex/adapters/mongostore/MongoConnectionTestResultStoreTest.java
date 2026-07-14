package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectionTestItem;
import io.cyntex.spi.store.ConnectionTestResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The connection-test-result document codec is the mapping core of the result store: the latest result
 * for a connection is stored as one document keyed by the connection id — its overall outcome, the
 * connector-reported items with their optional diagnostics, and the test time — and reconstructed from it
 * on read. These witness the mapping deterministically, without a Mongo server: the document shape, a
 * full round-trip (a passed item, a warning item, and a failed item carrying every diagnostic), an empty
 * item list, an item with no diagnostics, and that a structurally corrupt stored document surfaces as a
 * coded {@code io.document-unreadable} diagnostic rather than a bare crash. A real Mongo round-trip is
 * exercised by {@code MongoConnectionTestResultStoreIT} (skipped where Docker is absent).
 */
class MongoConnectionTestResultStoreTest {

    private static ConnectionTestResult ordersResult() {
        return new ConnectionTestResult(
                "conn-mongo-orders",
                "mongodb",
                ConnectionTestResult.Outcome.FAILED,
                List.of(
                        new ConnectionTestItem("Connection", ConnectionTestItem.Status.PASSED, null, null, null, null),
                        new ConnectionTestItem(
                                "Time detection",
                                ConnectionTestItem.Status.WARNING,
                                "server clock drifts 3.2s from engine clock",
                                null,
                                null,
                                null),
                        new ConnectionTestItem(
                                "Login",
                                ConnectionTestItem.Status.FAILED,
                                "authentication failed for user 'sync'",
                                "SCRAM-SHA-256 negotiation rejected",
                                "verify the user exists in the admin database",
                                "11000")),
                1783939200000L);
    }

    @Test
    void documentCarriesIdConnectorOutcomeItemsAndTestedAt() {
        Document document = MongoConnectionTestResultStore.toDocument(ordersResult());

        assertThat(document.getString("_id")).isEqualTo("conn-mongo-orders");
        assertThat(document.getString("connectorId")).isEqualTo("mongodb");
        assertThat(document.getString("outcome")).isEqualTo("FAILED");
        assertThat(document.getLong("testedAt")).isEqualTo(1783939200000L);

        List<Document> items = document.getList("items", Document.class);
        assertThat(items)
                .extracting(i -> i.getString("name"), i -> i.getString("status"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Connection", "PASSED"),
                        org.assertj.core.groups.Tuple.tuple("Time detection", "WARNING"),
                        org.assertj.core.groups.Tuple.tuple("Login", "FAILED"));
        Document login = items.get(2);
        assertThat(login.getString("message")).isEqualTo("authentication failed for user 'sync'");
        assertThat(login.getString("reason")).isEqualTo("SCRAM-SHA-256 negotiation rejected");
        assertThat(login.getString("solution")).isEqualTo("verify the user exists in the admin database");
        assertThat(login.getString("connectorErrorCode")).isEqualTo("11000");
    }

    @Test
    void roundTripReconstructsTheSameResult() {
        ConnectionTestResult result = ordersResult();

        assertThat(MongoConnectionTestResultStore.toResult(MongoConnectionTestResultStore.toDocument(result)))
                .isEqualTo(result);
    }

    @Test
    void emptyItemsRoundTrips() {
        ConnectionTestResult result = new ConnectionTestResult(
                "bare", "mysql", ConnectionTestResult.Outcome.PASSED, List.of(), 42L);

        assertThat(MongoConnectionTestResultStore.toResult(MongoConnectionTestResultStore.toDocument(result)))
                .isEqualTo(result);
    }

    @Test
    void anItemWithNoDiagnosticsRoundTripsWithNulls() {
        ConnectionTestResult result = new ConnectionTestResult(
                "x",
                "mysql",
                ConnectionTestResult.Outcome.PASSED,
                List.of(new ConnectionTestItem("Connection", ConnectionTestItem.Status.PASSED, null, null, null, null)),
                7L);

        ConnectionTestResult read =
                MongoConnectionTestResultStore.toResult(MongoConnectionTestResultStore.toDocument(result));

        ConnectionTestItem item = read.items().get(0);
        assertThat(item.message()).isNull();
        assertThat(item.reason()).isNull();
        assertThat(item.solution()).isNull();
        assertThat(item.connectorErrorCode()).isNull();
        assertThat(read).isEqualTo(result);
    }

    @Test
    void toResultWithAnAbsentItemsFieldReadsBackEmpty() {
        Document document = new Document("_id", "bare")
                .append("connectorId", "mysql")
                .append("outcome", "PASSED")
                .append("testedAt", 42L);

        assertThat(MongoConnectionTestResultStore.toResult(document).items()).isEmpty();
    }

    @Test
    void toResultMissingConnectorIdIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "conn-x").append("outcome", "PASSED").append("testedAt", 1L);

        Throwable thrown = catchThrowable(() -> MongoConnectionTestResultStore.toResult(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "conn-x");
    }

    @Test
    void toResultWithAnUnknownOutcomeIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "conn-x")
                .append("connectorId", "mysql")
                .append("outcome", "MAYBE")
                .append("testedAt", 1L);

        Throwable thrown = catchThrowable(() -> MongoConnectionTestResultStore.toResult(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }

    @Test
    void toResultWithAnAbsentTestedAtIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "conn-x").append("connectorId", "mysql").append("outcome", "PASSED");

        Throwable thrown = catchThrowable(() -> MongoConnectionTestResultStore.toResult(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }

    @Test
    void toResultOnAnItemMissingItsNameIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "conn-x")
                .append("connectorId", "mysql")
                .append("outcome", "PASSED")
                .append("testedAt", 1L)
                .append("items", List.of(new Document("status", "PASSED")));

        Throwable thrown = catchThrowable(() -> MongoConnectionTestResultStore.toResult(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }

    @Test
    void toResultOnAnItemWithAnUnknownStatusIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "conn-x")
                .append("connectorId", "mysql")
                .append("outcome", "PASSED")
                .append("testedAt", 1L)
                .append("items", List.of(new Document("name", "Connection").append("status", "HUH")));

        Throwable thrown = catchThrowable(() -> MongoConnectionTestResultStore.toResult(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }

    @Test
    void toResultOnAnItemsFieldThatIsNotAListIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "conn-x")
                .append("connectorId", "mysql")
                .append("outcome", "PASSED")
                .append("testedAt", 1L)
                .append("items", "oops");

        Throwable thrown = catchThrowable(() -> MongoConnectionTestResultStore.toResult(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }
}
