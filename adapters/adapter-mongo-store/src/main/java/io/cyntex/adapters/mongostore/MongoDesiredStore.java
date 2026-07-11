package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.lifecycle.DesiredState;
import io.cyntex.core.lifecycle.PipelineState;
import io.cyntex.spi.store.DesiredStore;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The MongoDB pipeline desired-state store: one desired-intent document per pipeline, keyed by the
 * pipeline id (as {@code _id}), carrying the target state and the artifact revision the intent was
 * expressed against. Desired intent is plain, not fenced: it is a straight upsert by pipeline id
 * (last write wins), not the epoch-fencing compare-and-swap the actual state store uses.
 *
 * <p>Driver IO failures are translated into coded io diagnostics, so no driver type escapes the
 * module (rule R3). A stored document missing a field this version requires, or carrying a target
 * state this version does not recognize, is store corruption — surfaced as a coded io diagnostic,
 * not a bare crash while reconstructing.
 */
public final class MongoDesiredStore implements DesiredStore {

    private final MongoCollection<Document> collection;

    public MongoDesiredStore(MongoCollection<Document> collection) {
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    @Override
    public void save(DesiredState desired) {
        Objects.requireNonNull(desired, "desired");
        // Upsert by the pipeline id (the document _id): a re-save of the same pipeline overwrites in
        // place (last write wins) rather than accumulating documents. Desired intent is not fenced.
        StoreIo.run(() -> collection.replaceOne(
                new Document("_id", desired.pipelineId()), toDocument(desired), new ReplaceOptions().upsert(true)));
    }

    @Override
    public Optional<DesiredState> read(String pipelineId) {
        Objects.requireNonNull(pipelineId, "pipelineId");
        Document document = StoreIo.call(() -> collection.find(new Document("_id", pipelineId)).first());
        return document == null ? Optional.empty() : Optional.of(toDesired(document));
    }

    @Override
    public List<DesiredState> list() {
        // The driver read is wrapped so no MongoException escapes the module (rule R3); each document is
        // reconstructed outside it, so a corrupt one still surfaces as a coded io diagnostic, not a bare crash.
        List<Document> documents = StoreIo.call(() -> collection.find().into(new ArrayList<>()));
        return documents.stream().map(MongoDesiredStore::toDesired).toList();
    }

    /** Maps a desired state to its stored document: the pipeline id as {@code _id}, the rest as fields. */
    static Document toDocument(DesiredState desired) {
        return new Document("_id", desired.pipelineId())
                .append("targetState", desired.targetState().name())
                .append("revision", desired.revision());
    }

    /** Reconstructs a desired state from its stored document. */
    static DesiredState toDesired(Document document) {
        String id = document.getString("_id");
        String targetState = document.getString("targetState");
        String revision = document.getString("revision");
        if (targetState == null || revision == null) {
            // A stored desired doc missing a field this version requires is store corruption, surfaced
            // as a coded io diagnostic rather than a bare null-argument crash while reconstructing.
            throw new CyntexException(IoError.DOCUMENT_UNREADABLE, Map.of("id", String.valueOf(id)), null);
        }
        return new DesiredState(id, parseState(targetState, id), revision);
    }

    /** A stored target state this version does not recognize is corruption, not a bare enum-valueOf crash. */
    private static PipelineState parseState(String targetState, String id) {
        try {
            return PipelineState.valueOf(targetState);
        } catch (IllegalArgumentException e) {
            throw new CyntexException(IoError.DOCUMENT_UNREADABLE, Map.of("id", String.valueOf(id)), e);
        }
    }
}
