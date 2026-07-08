package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.User;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The user codec is the mapping core of the user store: a user is stored as one document keyed by its
 * username ({@code _id}), so a save upserts in place rather than accumulating duplicates. This
 * witnesses the mapping deterministically, without a Mongo server; a real Mongo round-trip is
 * exercised by {@code MongoUserStoreIT} (skipped where Docker is absent). A stored document missing a
 * required field is surfaced as a storage-integrity diagnostic, not a leaked authoring error.
 */
class MongoUserStoreTest {

    @Test
    void documentIsKeyedByUsernameAndCarriesTheFields() {
        Document document = MongoUserStore.toDocument(new User("alice", "hash-abc", "admin"));

        assertThat(document.getString("_id")).isEqualTo("alice");
        assertThat(document.getString("passwordHash")).isEqualTo("hash-abc");
        assertThat(document.getString("role")).isEqualTo("admin");
    }

    @Test
    void documentRoundTripsBackToTheSameUser() {
        User user = new User("alice", "hash-abc", "admin");

        User back = MongoUserStore.toUser(MongoUserStore.toDocument(user));

        assertThat(back).isEqualTo(user);
    }

    @Test
    void aDocumentMissingARequiredFieldIsSurfacedAsUnreadable() {
        Document partial = new Document("_id", "alice").append("role", "admin");

        Throwable thrown = catchThrowable(() -> MongoUserStore.toUser(partial));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(((CyntexException) thrown).args()).containsEntry("id", "alice");
    }
}
