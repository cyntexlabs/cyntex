package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.lifecycle.DesiredState;
import io.cyntex.core.lifecycle.PipelineState;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The desired-document codec is the mapping core of the desired-state store: a desired intent is
 * stored as a structured document — its pipeline id as {@code _id}, its target state and revision as
 * fields — and reconstructed from it on read. These witness the mapping deterministically, without a
 * Mongo server. A real Mongo round-trip is exercised by {@code MongoDesiredStoreIT} (skipped where
 * Docker is absent).
 */
class MongoDesiredStoreTest {

    @Test
    void documentCarriesIdTargetStateAndRevision() {
        DesiredState desired = new DesiredState("orders_sync", PipelineState.RUNNING, "rev-abc");

        Document document = MongoDesiredStore.toDocument(desired);

        assertThat(document.getString("_id")).isEqualTo("orders_sync");
        assertThat(document.getString("targetState")).isEqualTo("RUNNING");
        assertThat(document.getString("revision")).isEqualTo("rev-abc");
    }

    @Test
    void roundTripReconstructsTheSameDesiredState() {
        DesiredState desired = new DesiredState("orders_sync", PipelineState.PAUSED, "rev-9");

        assertThat(MongoDesiredStore.toDesired(MongoDesiredStore.toDocument(desired))).isEqualTo(desired);
    }

    @Test
    void toDesiredOnADocumentMissingTargetStateIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders_sync").append("revision", "rev-abc");

        Throwable thrown = catchThrowable(() -> MongoDesiredStore.toDesired(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "orders_sync");
    }

    @Test
    void toDesiredOnADocumentMissingRevisionIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders_sync").append("targetState", "RUNNING");

        Throwable thrown = catchThrowable(() -> MongoDesiredStore.toDesired(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }

    @Test
    void toDesiredOnAnUnrecognizedTargetStateIsDocumentUnreadable() {
        // A stored target state this version does not recognize is corruption, surfaced as a coded io
        // diagnostic rather than a bare enum-valueOf crash while reconstructing.
        Document corrupt = new Document("_id", "orders_sync")
                .append("targetState", "TELEPORTING")
                .append("revision", "rev-abc");

        Throwable thrown = catchThrowable(() -> MongoDesiredStore.toDesired(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }
}
