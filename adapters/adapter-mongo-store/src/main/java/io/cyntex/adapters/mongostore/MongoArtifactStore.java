package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.ReplaceOptions;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.spi.store.ArtifactStore;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
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
 * canonical text as the body. Driver IO failures during save / get / list are not yet translated
 * into coded diagnostics; that lands with the storage IO error domain.
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
        collection.replaceOne(
                new Document("_id", artifact.id()), toDocument(artifact), new ReplaceOptions().upsert(true));
    }

    @Override
    public Optional<Resource> get(String id) {
        Objects.requireNonNull(id, "id");
        Document document = collection.find(new Document("_id", id)).first();
        return document == null ? Optional.empty() : Optional.of(toResource(document));
    }

    @Override
    public List<Resource> list() {
        List<Resource> resources = new ArrayList<>();
        // Iterate over an explicitly-closed cursor so the server-side cursor is released even when a
        // stored document fails to reconstruct mid-iteration; a for-each over the iterable would not
        // close it on that exception path.
        try (MongoCursor<Document> cursor = collection.find().iterator()) {
            while (cursor.hasNext()) {
                resources.add(toResource(cursor.next()));
            }
        }
        return resources;
    }

    /** Maps a resource to its stored document: the id and kind as structured fields, canonical text as body. */
    static Document toDocument(Resource artifact) {
        return new Document("_id", artifact.id())
                .append("kind", artifact.kind())
                .append("canonical", WRITER.write(artifact));
    }

    /** Reconstructs a resource from its stored document by parsing the canonical body. */
    static Resource toResource(Document document) {
        return PARSER.parse(document.getString("canonical"));
    }
}
