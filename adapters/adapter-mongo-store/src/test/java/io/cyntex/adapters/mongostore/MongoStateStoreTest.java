package io.cyntex.adapters.mongostore;

import io.cyntex.core.lifecycle.CheckpointDoc;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

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
}
