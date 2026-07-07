package io.cyntex.adapters.mongostore;

import io.cyntex.spi.store.AuditRecord;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit-record codec is the mapping core of the audit store: an audit record is stored as one
 * append-only document carrying the subject, the operation, the target and the attempt time. This
 * witnesses the mapping deterministically, without a Mongo server; the timestamp is stored as epoch
 * millis (a plain long) so no JSR-310 codec is required, matching the state store. A real Mongo
 * append is exercised by {@code MongoAuditStoreIT} (skipped where Docker is absent).
 */
class MongoAuditStoreTest {

    private static final Instant TS = Instant.parse("2026-07-07T10:15:30Z");

    @Test
    void documentCarriesTheAuditFields() {
        AuditRecord record = new AuditRecord(TS, "alice", "artifact.apply", "orders-source");

        Document document = MongoAuditStore.toDocument(record);

        assertThat(document.getLong("ts")).isEqualTo(TS.toEpochMilli());
        assertThat(document.getString("principal")).isEqualTo("alice");
        assertThat(document.getString("operationId")).isEqualTo("artifact.apply");
        assertThat(document.getString("resourceId")).isEqualTo("orders-source");
    }

    @Test
    void documentCarriesNoIdSoEachRecordIsADistinctAppend() {
        Document document = MongoAuditStore.toDocument(new AuditRecord(TS, "alice", "artifact.apply", "orders-source"));

        // No _id is assigned by the codec: an audit log is append-only, so every record is a distinct
        // document (the driver assigns a fresh ObjectId) rather than an upsert keyed by some field.
        assertThat(document.containsKey("_id")).isFalse();
    }
}
