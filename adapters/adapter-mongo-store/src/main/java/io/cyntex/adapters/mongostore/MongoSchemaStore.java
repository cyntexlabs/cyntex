package io.cyntex.adapters.mongostore;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.SchemaStore;
import io.cyntex.spi.store.SourceField;
import io.cyntex.spi.store.SourceIndex;
import io.cyntex.spi.store.SourceModel;
import io.cyntex.spi.store.SourceTable;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The MongoDB source-schema store: stores the discovered source model for a connection as one
 * structured document keyed by the connection's id, holding the tables (with their fields, primary key
 * and indexes) as nested sub-documents.
 *
 * <p>The model is a fixed shape of plain scalars and lists, so it is mapped field by field rather than
 * through a generic value normalization; on read the driver's {@code Document} / list values are
 * reconstructed into the pure model, so no driver type escapes this module (rule R3). A stored document
 * whose shape cannot be reconstructed is surfaced as a coded {@code io.document-unreadable} diagnostic.
 */
public final class MongoSchemaStore implements SchemaStore {

    private final MongoCollection<Document> collection;

    public MongoSchemaStore(MongoCollection<Document> collection) {
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    @Override
    public void save(String connectionId, SourceModel model) {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(model, "model");
        // Upsert by the connection id (the document _id): the stored form is a full replacement, so a
        // re-discovery of the same connection overwrites in place rather than accumulating documents.
        StoreIo.run(() -> collection.replaceOne(
                new Document("_id", connectionId), toDocument(connectionId, model), new ReplaceOptions().upsert(true)));
    }

    @Override
    public Optional<SourceModel> get(String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        Document document = StoreIo.call(() -> collection.find(new Document("_id", connectionId)).first());
        return document == null ? Optional.empty() : Optional.of(toModel(document));
    }

    /** Maps a discovered source model to its stored document: the connection id as {@code _id}, tables as a field. */
    static Document toDocument(String connectionId, SourceModel model) {
        List<Document> tables = new ArrayList<>();
        for (SourceTable table : model.tables()) {
            tables.add(tableDocument(table));
        }
        return new Document("_id", connectionId).append("tables", tables);
    }

    private static Document tableDocument(SourceTable table) {
        List<Document> fields = new ArrayList<>();
        for (SourceField field : table.fields()) {
            // type is null when discovery could not resolve it; stored as a null value, read back as null.
            fields.add(new Document("name", field.name()).append("type", field.type()));
        }
        List<Document> indexes = new ArrayList<>();
        for (SourceIndex index : table.indexes()) {
            indexes.add(new Document("name", index.name())
                    .append("fields", List.copyOf(index.fields()))
                    .append("unique", index.unique()));
        }
        return new Document("name", table.name())
                .append("fields", fields)
                .append("primaryKey", List.copyOf(table.primaryKey()))
                .append("indexes", indexes);
    }

    /** Reconstructs a source model from its stored document, or fails coded when the shape is unreadable. */
    static SourceModel toModel(Document document) {
        String id = document.getString("_id");
        List<SourceTable> tables = new ArrayList<>();
        for (Document table : documentList(document.get("tables"), id)) {
            tables.add(toTable(table, id));
        }
        return new SourceModel(tables);
    }

    private static SourceTable toTable(Document table, String id) {
        String name = table.getString("name");
        if (name == null) {
            throw unreadable(id);
        }
        List<SourceField> fields = new ArrayList<>();
        for (Document field : documentList(table.get("fields"), id)) {
            String fieldName = field.getString("name");
            if (fieldName == null) {
                throw unreadable(id);
            }
            fields.add(new SourceField(fieldName, field.getString("type")));
        }
        List<SourceIndex> indexes = new ArrayList<>();
        for (Document index : documentList(table.get("indexes"), id)) {
            String indexName = index.getString("name");
            if (indexName == null) {
                throw unreadable(id);
            }
            indexes.add(new SourceIndex(indexName, stringList(index.get("fields"), id), unique(index, id)));
        }
        return new SourceTable(name, fields, stringList(table.get("primaryKey"), id), indexes);
    }

    /** Reads a stored array-of-documents field: an absent field is empty, a non-array or non-document element is corrupt. */
    private static List<Document> documentList(Object value, String id) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw unreadable(id);
        }
        List<Document> documents = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Document document)) {
                throw unreadable(id);
            }
            documents.add(document);
        }
        return documents;
    }

    /** Reads a stored array-of-strings field: an absent field is empty, a non-array or non-string element is corrupt. */
    private static List<String> stringList(Object value, String id) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw unreadable(id);
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String string)) {
                throw unreadable(id);
            }
            strings.add(string);
        }
        return strings;
    }

    /** Reads an index's unique flag: an absent flag reads as false, a present non-boolean is corrupt. */
    private static boolean unique(Document index, String id) {
        Object value = index.get("unique");
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw unreadable(id);
        }
        return flag;
    }

    private static CyntexException unreadable(String id) {
        // A stored schema document whose shape cannot be reconstructed is store corruption, surfaced as a
        // coded io diagnostic rather than a bare crash while reconstructing.
        return new CyntexException(IoError.DOCUMENT_UNREADABLE, Map.of("id", String.valueOf(id)), null);
    }
}
