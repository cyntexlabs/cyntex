package io.cyntex.adapters.mongostore;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.lifecycle.CheckpointDoc;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The checkpoint-document codec is the mapping core of the state store: a checkpoint is stored as a
 * document keyed by the pipeline id and reconstructed from it on read. These witness the mapping
 * deterministically, without a Mongo server. The real compare-and-swap behavior — apply, fence,
 * monotonic epoch, and the two-writer race — is exercised by {@code MongoStateStoreIT} (skipped
 * where Docker is absent).
 */
class MongoStateStoreTest {

    // A sub-second instant, so the millisecond leg of the touchMillis round-trip is actually witnessed:
    // the stored form keeps millisecond precision (touchMillis) and this value round-trips exactly.
    private static final Instant TOUCH = Instant.parse("2026-07-06T12:00:00.123Z");

    @Test
    void checkpointRoundTripsThroughTheDocumentMapping() {
        CheckpointDoc checkpoint = new CheckpointDoc("orders-sync", "{\"state\":\"RUNNING\"}", 3, TOUCH);

        Document document = MongoStateStore.toDocument(checkpoint);

        assertThat(document.getString("_id")).isEqualTo("orders-sync");
        assertThat(document.getString("stateJson")).isEqualTo("{\"state\":\"RUNNING\"}");
        assertThat(document.getLong("epoch")).isEqualTo(3L);
        assertThat(document.getLong("touchMillis")).isEqualTo(TOUCH.toEpochMilli());
        assertThat(MongoStateStore.toCheckpoint(document)).isEqualTo(checkpoint);
    }

    @Test
    void initialCheckpointMapsAtEpochZero() {
        Document document = MongoStateStore.toDocument(CheckpointDoc.initial("orders-sync", "{}", TOUCH));

        assertThat(document.getLong("epoch")).isEqualTo(0L);
        assertThat(MongoStateStore.toCheckpoint(document).epoch()).isZero();
    }

    @Test
    void toCheckpointOnADocumentMissingAFieldIsDocumentUnreadable() {
        // A stored checkpoint missing a field this version requires (here: epoch) is store corruption,
        // surfaced as a coded io diagnostic rather than a bare unboxing NPE.
        Document corrupt = new Document("_id", "orders-sync").append("stateJson", "{}").append("touchMillis", 0L);

        Throwable thrown = catchThrowable(() -> MongoStateStore.toCheckpoint(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "orders-sync");
    }

    @Test
    void createDuplicateKeyIsAnOrderingErrorAndOtherWriteFailuresAreCodedIo() {
        // A duplicate _id (re-seed) is a caller ordering error, surfaced bare; any other driver write
        // failure during the seed is a coded io diagnostic. Witnessed here deterministically, without
        // a server, by classifying constructed driver write errors.
        MongoException duplicate = new MongoWriteException(
                new WriteError(11000, "E11000 duplicate key", new BsonDocument()), new ServerAddress(), Set.of());
        assertThat(MongoStateStore.classifyInsertFailure(duplicate, "p1"))
                .isInstanceOf(IllegalStateException.class);

        MongoException validation = new MongoWriteException(
                new WriteError(121, "document validation failure", new BsonDocument()), new ServerAddress(), Set.of());
        RuntimeException classified = MongoStateStore.classifyInsertFailure(validation, "p1");
        assertThat(classified).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) classified).code()).isEqualTo(IoError.STORE_UNAVAILABLE);
    }
}
