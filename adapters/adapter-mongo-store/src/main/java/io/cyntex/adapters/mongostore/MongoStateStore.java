package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import io.cyntex.core.lifecycle.CasOutcome;
import io.cyntex.core.lifecycle.CheckpointDoc;
import io.cyntex.spi.store.StateStore;
import org.bson.Document;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The MongoDB pipeline-state store: one checkpoint document per pipeline, whose transitions land only
 * through an epoch-fencing compare-and-swap. The atomic conditional update on the stored epoch is the
 * fence — it is what makes two owners that both believe they hold the pipeline unable to both write.
 *
 * <p>The document is keyed by the pipeline id (as {@code _id}) and carries the state payload
 * ({@code stateJson}), the monotonic fencing epoch ({@code epoch}), and the last-write timestamp
 * ({@code touchMillis}). The epoch alone decides the swap; the state and the timestamp never do, so a
 * lagging clock or a stale state view cannot corrupt the fencing decision.
 *
 * <p>Driver IO failures during read / create / swap are not yet translated into coded diagnostics;
 * that lands with the storage IO error domain. A create that collides with an existing checkpoint and
 * a swap on an unseeded pipeline are caller ordering errors, left to surface as-is.
 */
public final class MongoStateStore implements StateStore {

    private static final FindOneAndUpdateOptions RETURN_AFTER =
            new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER);

    private final MongoCollection<Document> collection;

    public MongoStateStore(MongoCollection<Document> collection) {
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    @Override
    public Optional<CheckpointDoc> read(String pipelineId) {
        Objects.requireNonNull(pipelineId, "pipelineId");
        Document document = collection.find(new Document("_id", pipelineId)).first();
        return document == null ? Optional.empty() : Optional.of(toCheckpoint(document));
    }

    @Override
    public void create(String pipelineId, String stateJson, Instant touchTime) {
        // Insert-only: insertOne fails on a duplicate _id, so a checkpoint that already exists is never
        // overwritten — overwriting would reset the fencing epoch that compareAndSwap maintains.
        collection.insertOne(toDocument(CheckpointDoc.initial(pipelineId, stateJson, touchTime)));
    }

    @Override
    public CasOutcome compareAndSwap(String pipelineId, long expectedEpoch, String nextStateJson, Instant touchTime) {
        Objects.requireNonNull(pipelineId, "pipelineId");
        Objects.requireNonNull(nextStateJson, "nextStateJson");
        Objects.requireNonNull(touchTime, "touchTime");
        // The atomic fence: swap the state and bump the epoch only where the stored epoch still equals
        // the writer's expectation. This is the sole legal transition write.
        Document filter = new Document("_id", pipelineId).append("epoch", expectedEpoch);
        Document update = new Document("$set",
                new Document("stateJson", nextStateJson).append("touchMillis", touchTime.toEpochMilli()))
                .append("$inc", new Document("epoch", 1L));
        Document applied = collection.findOneAndUpdate(filter, update, RETURN_AFTER);
        if (applied != null) {
            return new CasOutcome.Applied(toCheckpoint(applied));
        }
        // No document matched the expected epoch. Read back to tell a fenced writer (a newer epoch
        // superseded it) from an ordering error (the pipeline was never seeded). The fence itself was
        // already decided by the atomic update above; this read only supplies the diagnostic epoch.
        Document current = collection.find(new Document("_id", pipelineId)).first();
        if (current == null) {
            throw new IllegalStateException(
                    "compareAndSwap on an unseeded pipeline: " + pipelineId + " (create must seed it first)");
        }
        return new CasOutcome.Fenced(current.getLong("epoch"));
    }

    /** Maps a checkpoint to its stored document: the pipeline id as {@code _id}, the rest as fields. */
    static Document toDocument(CheckpointDoc checkpoint) {
        return new Document("_id", checkpoint.pipelineId())
                .append("stateJson", checkpoint.stateJson())
                .append("epoch", checkpoint.epoch())
                .append("touchMillis", checkpoint.touchTime().toEpochMilli());
    }

    /** Reconstructs a checkpoint from its stored document. */
    static CheckpointDoc toCheckpoint(Document document) {
        return new CheckpointDoc(
                document.getString("_id"),
                document.getString("stateJson"),
                document.getLong("epoch"),
                Instant.ofEpochMilli(document.getLong("touchMillis")));
    }
}
