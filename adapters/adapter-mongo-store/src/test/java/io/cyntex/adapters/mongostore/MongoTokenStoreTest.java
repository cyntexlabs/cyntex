package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.TokenRecord;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The token codec is the mapping core of the token store: a token is stored as one document keyed by
 * its id ({@code _id}), the creation time as epoch millis, the rest as plain fields. This witnesses
 * the mapping deterministically, without a Mongo server; a real round-trip is exercised by {@code
 * MongoTokenStoreIT} (skipped where Docker is absent). A stored document missing a required field is
 * surfaced as a storage-integrity diagnostic, not a bare crash or a leaked authoring error.
 */
class MongoTokenStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");

    @Test
    void documentIsKeyedByTokenIdAndCarriesTheFields() {
        Document document = MongoTokenStore.toDocument(
                new TokenRecord("tok-1", "WRITE", "hash-abc", false, NOW));

        assertThat(document.getString("_id")).isEqualTo("tok-1");
        assertThat(document.getString("scope")).isEqualTo("WRITE");
        assertThat(document.getString("secretHash")).isEqualTo("hash-abc");
        assertThat(document.getBoolean("revoked")).isFalse();
        assertThat(document.getLong("createdAt")).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void documentRoundTripsBackToTheSameRecord() {
        TokenRecord record = new TokenRecord("tok-1", "ADMIN", "hash-xyz", true, NOW);

        TokenRecord back = MongoTokenStore.toRecord(MongoTokenStore.toDocument(record));

        assertThat(back).isEqualTo(record);
    }

    @Test
    void aDocumentMissingASecretHashIsSurfacedAsUnreadable() {
        Document partial = new Document("_id", "tok-1")
                .append("scope", "WRITE")
                .append("revoked", false)
                .append("createdAt", NOW.toEpochMilli());

        Throwable thrown = catchThrowable(() -> MongoTokenStore.toRecord(partial));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(((CyntexException) thrown).args()).containsEntry("id", "tok-1");
    }

    @Test
    void aDocumentWithAWrongTypeFieldIsSurfacedAsUnreadable() {
        // A field written with the wrong BSON type (createdAt as a String) must not crash bare with a
        // ClassCastException escaping the module; it is a storage-integrity fault surfaced as a coded
        // io diagnostic, the same as a missing field.
        Document wrongType = new Document("_id", "tok-1")
                .append("scope", "WRITE")
                .append("secretHash", "hash-abc")
                .append("revoked", false)
                .append("createdAt", "not-a-long");

        Throwable thrown = catchThrowable(() -> MongoTokenStore.toRecord(wrongType));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(((CyntexException) thrown).args()).containsEntry("id", "tok-1");
    }

    @Test
    void aDocumentMissingTheRevokedFlagFailsClosedAsUnreadable() {
        // An absent revoked flag must never read as a valid (non-revoked) token.
        Document partial = new Document("_id", "tok-1")
                .append("scope", "WRITE")
                .append("secretHash", "hash-abc")
                .append("createdAt", NOW.toEpochMilli());

        Throwable thrown = catchThrowable(() -> MongoTokenStore.toRecord(partial));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }
}
