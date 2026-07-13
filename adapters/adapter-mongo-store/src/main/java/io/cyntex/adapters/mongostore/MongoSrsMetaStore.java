package io.cyntex.adapters.mongostore;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConsumerOffset;
import io.cyntex.spi.store.SchemaVersion;
import io.cyntex.spi.store.SrsMeta;
import io.cyntex.spi.store.SrsMetaStore;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The MongoDB SRS meta store: one durable coordination document per mining chain — the offset, consumer
 * cursor and schema truth that outlives the in-memory change ring. The document is keyed by the mining
 * chain id (as {@code _id}); each facet is advanced by its own atomic update, so a consumer that sets
 * its own cursor never clobbers another consumer's concurrent set or the chain's offset advance.
 *
 * <p>The consumer cursors are stored as a sub-document keyed by pipeline id — a resource id, which the
 * grammar forbids from containing a dot, so the id is a safe update path and one consumer's cursor is
 * set at {@code consumerOffsets.<pipelineId>} independently. The schema history is an append-only array
 * advanced by {@code $push}. The nullable positions are stored only when present, never as explicit
 * nulls.
 *
 * <p>Driver IO failures are translated into coded io diagnostics, so no driver type escapes the module
 * (rule R3). A re-seed of an existing chain (which would discard its accumulated truth) and a mutate of
 * an unseeded chain are caller ordering errors — surfaced bare (an {@code IllegalStateException}), not
 * laundered into an io code that would hide the defect. A stored document that cannot be read back into
 * its model is coded {@code io.document-unreadable}.
 */
public final class MongoSrsMetaStore implements SrsMetaStore {

    private final MongoCollection<Document> collection;

    public MongoSrsMetaStore(MongoCollection<Document> collection) {
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    @Override
    public Optional<SrsMeta> read(String miningChainId) {
        Objects.requireNonNull(miningChainId, "miningChainId");
        Document document = StoreIo.call(() -> collection.find(new Document("_id", miningChainId)).first());
        return document == null ? Optional.empty() : Optional.of(toMeta(document));
    }

    @Override
    public void create(String miningChainId, String retention) {
        // Insert-only: insertOne fails on a duplicate _id, so an existing chain's accumulated offset /
        // cursor / schema truth is never discarded by a re-seed.
        Document document = toDocument(new SrsMeta(miningChainId, null, List.of(), null, List.of(), retention));
        try {
            collection.insertOne(document);
        } catch (MongoException e) {
            throw classifyInsertFailure(e, miningChainId);
        }
    }

    @Override
    public void advanceSourceReadOffset(String miningChainId, String sourceReadOffset) {
        Objects.requireNonNull(sourceReadOffset, "sourceReadOffset");
        update(miningChainId, new Document("$set", new Document("sourceReadOffset", sourceReadOffset)));
    }

    @Override
    public void upsertConsumerOffset(String miningChainId, ConsumerOffset offset) {
        Objects.requireNonNull(offset, "offset");
        // Keyed by the dot-free pipeline id, so one consumer's cursor is set independently of the others'.
        update(miningChainId, new Document("$set",
                new Document("consumerOffsets." + offset.pipelineId(), consumerToDocument(offset))));
    }

    @Override
    public void advanceConsumerReadSeq(String miningChainId, String pipelineId, String table, long lastReadSeq) {
        update(miningChainId, consumerReadSeqUpdate(pipelineId, table, lastReadSeq));
    }

    @Override
    public void advanceSinkAckedSrcpos(String miningChainId, String pipelineId, String srcpos) {
        update(miningChainId, sinkAckedSrcposUpdate(pipelineId, srcpos));
    }

    /**
     * The path-scoped update advancing one consumer's read cursor for one table: it sets only
     * {@code consumerOffsets.<pipelineId>.perTableSeq.<table>}, so the consumer's sink-acked position is
     * left untouched by a read-cursor advance. Both keys are dot-free — the pipeline id is a resource id
     * the grammar forbids a dot in, and an L1 stream name is a bare identifier — so the dotted path
     * addresses exactly one field. A deep {@code $set} creates the intermediate objects, so a reader may
     * advance before the consumer has any other cursor state.
     */
    static Document consumerReadSeqUpdate(String pipelineId, String table, long lastReadSeq) {
        Objects.requireNonNull(pipelineId, "pipelineId");
        Objects.requireNonNull(table, "table");
        return new Document("$set",
                new Document("consumerOffsets." + pipelineId + ".perTableSeq." + table, lastReadSeq));
    }

    /**
     * The path-scoped update that advances one consumer's durable sink-acked position: a {@code $set} on
     * {@code consumerOffsets.<pipelineId>.sinkAckedSrcpos}, so the reader's per-table cursor is left
     * untouched by a sink-ack advance. The pipeline id is a resource id the grammar forbids a dot in, so
     * the dotted path addresses exactly one field. A deep {@code $set} creates the intermediate objects, so
     * a sink may ack before the consumer has any other cursor state.
     */
    static Document sinkAckedSrcposUpdate(String pipelineId, String srcpos) {
        Objects.requireNonNull(pipelineId, "pipelineId");
        Objects.requireNonNull(srcpos, "srcpos");
        return new Document("$set",
                new Document("consumerOffsets." + pipelineId + ".sinkAckedSrcpos", srcpos));
    }

    @Override
    public void setCdcStartPosition(String miningChainId, String cdcStartPosition) {
        Objects.requireNonNull(cdcStartPosition, "cdcStartPosition");
        update(miningChainId, new Document("$set", new Document("cdcStartPosition", cdcStartPosition)));
    }

    @Override
    public void appendSchemaVersion(String miningChainId, SchemaVersion version) {
        Objects.requireNonNull(version, "version");
        update(miningChainId, new Document("$push", new Document("schemaHistory", schemaToDocument(version))));
    }

    /**
     * Applies an atomic update to a seeded chain. A zero matched count means no document carried the id:
     * the chain was never seeded, a caller ordering error surfaced bare (not laundered into an io code).
     */
    private void update(String miningChainId, Document update) {
        Objects.requireNonNull(miningChainId, "miningChainId");
        UpdateResult result = StoreIo.call(() -> collection.updateOne(new Document("_id", miningChainId), update));
        if (result.getMatchedCount() == 0) {
            throw new IllegalStateException("srs meta mutate on an unseeded mining chain: " + miningChainId
                    + " (create must seed it first)");
        }
    }

    /**
     * Classifies a failed seed insert: a duplicate {@code _id} is a caller ordering error (the chain was
     * already seeded), surfaced bare like the unseeded-mutate ordering error — not laundered into an io
     * code that would hide it; any other driver failure is a coded io diagnostic.
     */
    static RuntimeException classifyInsertFailure(MongoException e, String miningChainId) {
        if (e instanceof MongoWriteException write && write.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
            return new IllegalStateException(
                    "create on an already-seeded mining chain: " + miningChainId + " (create is insert-only)", e);
        }
        return StoreIo.coded(e);
    }

    /** Maps a meta record to its stored document: the mining chain id as {@code _id}, the rest as fields. */
    static Document toDocument(SrsMeta meta) {
        Document consumers = new Document();
        for (ConsumerOffset offset : meta.consumerOffsets()) {
            consumers.append(offset.pipelineId(), consumerToDocument(offset));
        }
        List<Document> schemaHistory = new ArrayList<>();
        for (SchemaVersion version : meta.schemaHistory()) {
            schemaHistory.add(schemaToDocument(version));
        }
        // The structural fields are always present (empty when seeded); the nullable positions are
        // appended only when set, so a seed reads back as a seed rather than as corruption.
        Document document = new Document("_id", meta.miningChainId())
                .append("consumerOffsets", consumers)
                .append("schemaHistory", schemaHistory);
        if (meta.sourceReadOffset() != null) {
            document.append("sourceReadOffset", meta.sourceReadOffset());
        }
        if (meta.cdcStartPosition() != null) {
            document.append("cdcStartPosition", meta.cdcStartPosition());
        }
        if (meta.retention() != null) {
            document.append("retention", meta.retention());
        }
        return document;
    }

    /** Reconstructs a meta record from its stored document. */
    static SrsMeta toMeta(Document document) {
        String id = document.getString("_id");
        Object consumersRaw = document.get("consumerOffsets");
        Object schemaRaw = document.get("schemaHistory");
        if (id == null || !(consumersRaw instanceof Document consumersDoc) || !(schemaRaw instanceof List<?> entries)) {
            // A stored meta missing a field this version requires is store corruption, surfaced as a
            // coded io diagnostic rather than a bare cast / unboxing crash while reconstructing.
            throw new CyntexException(IoError.DOCUMENT_UNREADABLE, Map.of("id", String.valueOf(id)), null);
        }
        List<ConsumerOffset> consumers = new ArrayList<>();
        for (Map.Entry<String, Object> entry : consumersDoc.entrySet()) {
            consumers.add(consumerFromDocument(entry.getKey(), asDocument(entry.getValue(), id)));
        }
        List<SchemaVersion> schemaHistory = new ArrayList<>();
        for (Object entry : entries) {
            schemaHistory.add(schemaFromDocument(asDocument(entry, id), id));
        }
        return new SrsMeta(id, document.getString("sourceReadOffset"), consumers,
                document.getString("cdcStartPosition"), schemaHistory, document.getString("retention"));
    }

    /** Reconstructs one consumer cursor from its stored sub-document, keyed by the pipeline id. */
    private static ConsumerOffset consumerFromDocument(String pipelineId, Document document) {
        Map<String, Long> perTableSeq = new LinkedHashMap<>();
        Object perTableRaw = document.get("perTableSeq");
        if (perTableRaw instanceof Document perTableDoc) {
            for (Map.Entry<String, Object> entry : perTableDoc.entrySet()) {
                perTableSeq.put(entry.getKey(), ((Number) entry.getValue()).longValue());
            }
        } else if (perTableRaw != null) {
            // Present but not a sub-document is store corruption. Absent is a valid sink-ack-only consumer:
            // the sink created the entry (a sinkAckedSrcpos-only $set) before the reader published any
            // per-table cursor, mirroring how an absent sinkAckedSrcpos reads back as null. It reads as an
            // empty cursor rather than as corruption.
            throw new CyntexException(IoError.DOCUMENT_UNREADABLE, Map.of("id", pipelineId), null);
        }
        return new ConsumerOffset(pipelineId, perTableSeq, document.getString("sinkAckedSrcpos"));
    }

    /** Reconstructs one schema version from its stored sub-document. */
    private static SchemaVersion schemaFromDocument(Document document, String miningChainId) {
        Long version = document.getLong("version");
        Long ddlSeq = document.getLong("ddlSeq");
        Object schemaRaw = document.get("schema");
        if (version == null || ddlSeq == null || !(schemaRaw instanceof Document schemaDoc)) {
            throw new CyntexException(IoError.DOCUMENT_UNREADABLE, Map.of("id", miningChainId), null);
        }
        return new SchemaVersion(version, new LinkedHashMap<>(schemaDoc), ddlSeq);
    }

    /** Reads a nested value as a document, or surfaces store corruption when it is not one. */
    private static Document asDocument(Object value, String miningChainId) {
        if (value instanceof Document document) {
            return document;
        }
        throw new CyntexException(IoError.DOCUMENT_UNREADABLE, Map.of("id", miningChainId), null);
    }

    /** Maps one consumer cursor to its stored sub-document (the per-table read cursor plus the acked position). */
    private static Document consumerToDocument(ConsumerOffset offset) {
        Document perTable = new Document();
        for (Map.Entry<String, Long> entry : offset.perTableSeq().entrySet()) {
            perTable.append(entry.getKey(), entry.getValue());
        }
        Document document = new Document("perTableSeq", perTable);
        if (offset.sinkAckedSrcpos() != null) {
            document.append("sinkAckedSrcpos", offset.sinkAckedSrcpos());
        }
        return document;
    }

    /** Maps one schema version to its stored sub-document. */
    private static Document schemaToDocument(SchemaVersion version) {
        return new Document("version", version.version())
                .append("schema", new Document(version.schema()))
                .append("ddlSeq", version.ddlSeq());
    }
}
