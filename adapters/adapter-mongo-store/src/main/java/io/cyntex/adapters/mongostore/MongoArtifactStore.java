package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.ReplaceOptions;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.spi.store.ArtifactStore;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The MongoDB artifact truth layer: stores each applied resource as one document keyed by the
 * resource's top-level id, holding the resource in its canonical form. Reading reconstructs the
 * resource from that canonical form through the canonical parser — the inverse of the canonical
 * writer — so the store keeps a single serialization contract and a written artifact reads back to
 * the same canonical form.
 *
 * <p>The document carries the id (as {@code _id}) and the kind as structured fields, and the
 * canonical text as the body. Driver IO failures during save / get / list are translated into coded
 * io diagnostics, and a stored body that no longer reconstructs is surfaced as an io diagnostic
 * rather than a leaked authoring code, so no driver type escapes the module (rule R3).
 */
public final class MongoArtifactStore implements ArtifactStore {

    private static final CanonicalWriter WRITER = new CanonicalWriter();
    private static final DslParser PARSER = new DslParser();

    private final MongoCollection<Document> collection;

    public MongoArtifactStore(MongoCollection<Document> collection) {
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    @Override
    public void save(Resource artifact) {
        Objects.requireNonNull(artifact, "artifact");
        // Upsert by the top-level id (the document _id): the stored form is a full replacement, so a
        // re-save of the same id overwrites in place rather than accumulating documents.
        StoreIo.run(() -> collection.replaceOne(
                new Document("_id", artifact.id()), toDocument(artifact), new ReplaceOptions().upsert(true)));
    }

    @Override
    public Optional<Resource> get(String id) {
        Objects.requireNonNull(id, "id");
        Document document = StoreIo.call(() -> collection.find(new Document("_id", id)).first());
        return document == null ? Optional.empty() : Optional.of(toResource(document));
    }

    @Override
    public List<Resource> list() {
        // A reconstruction failure (io.document-unreadable) passes through the driver-failure
        // translation untouched, and the explicitly-closed cursor is released even on that path — a
        // for-each over the iterable would not close it on the exception path.
        return StoreIo.call(() -> {
            List<Resource> resources = new ArrayList<>();
            try (MongoCursor<Document> cursor = collection.find().iterator()) {
                while (cursor.hasNext()) {
                    resources.add(toResource(cursor.next()));
                }
            }
            return resources;
        });
    }

    /** Maps a resource to its stored document: the id and kind as structured fields, canonical text as body. */
    static Document toDocument(Resource artifact) {
        return new Document("_id", artifact.id())
                .append("kind", artifact.kind())
                .append("canonical", WRITER.write(artifact));
    }

    /** Reconstructs a resource from its stored document by parsing the canonical body. */
    static Resource toResource(Document document) {
        String id = document.getString("_id");
        String canonical = document.getString("canonical");
        if (canonical == null) {
            throw new CyntexException(IoError.DOCUMENT_UNREADABLE, Map.of("id", String.valueOf(id)), null);
        }
        try {
            return PARSER.parse(canonical);
        } catch (RuntimeException e) {
            // A stored body that no longer reconstructs — corruption, or a newer grammar whose kind or
            // shape this version cannot build — is a storage-layer failure, surfaced as an io diagnostic
            // (with the original failure kept as the cause) rather than a leaked authoring code for a
            // document the user never authored. The catch is deliberately broad: a body from a newer
            // grammar can fail as more than a coded parse error (an unsupported kind, say), and all such
            // failures are the same storage-integrity signal.
            throw new CyntexException(IoError.DOCUMENT_UNREADABLE, Map.of("id", String.valueOf(id)), e);
        }
    }
}
