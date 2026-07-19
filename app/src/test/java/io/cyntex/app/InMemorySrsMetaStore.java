package io.cyntex.app;

import io.cyntex.spi.store.ConsumerOffset;
import io.cyntex.spi.store.SchemaVersion;
import io.cyntex.spi.store.SrsMeta;
import io.cyntex.spi.store.SrsMetaStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A faithful in-memory {@link SrsMetaStore} for the data-plane tests, synchronized so a Jet worker's
 * member-side cursor advance is visible to the test thread's read-back: insert-only create, per-facet
 * mutators that reject an unseeded chain, and a read-cursor advance that upserts one consumer's
 * {@code perTableSeq} without clobbering its sink-ack. Enough to exercise the capture run's provision,
 * cdc-start, offset and cursor wiring without a store backend.
 */
final class InMemorySrsMetaStore implements SrsMetaStore {

    private final Map<String, SrsMeta> records = new LinkedHashMap<>();

    @Override
    public synchronized Optional<SrsMeta> read(String miningChainId) {
        return Optional.ofNullable(records.get(miningChainId));
    }

    @Override
    public synchronized void create(String miningChainId, String retention) {
        if (records.containsKey(miningChainId)) {
            throw new IllegalStateException("mining chain already seeded: " + miningChainId);
        }
        records.put(miningChainId, new SrsMeta(miningChainId, null, List.of(), null, List.of(), retention));
    }

    @Override
    public synchronized void advanceSourceReadOffset(String miningChainId, String sourceReadOffset) {
        SrsMeta m = require(miningChainId);
        records.put(miningChainId, new SrsMeta(
                m.miningChainId(), sourceReadOffset, m.consumerOffsets(), m.cdcStartPosition(),
                m.schemaHistory(), m.retention()));
    }

    @Override
    public synchronized void upsertConsumerOffset(String miningChainId, ConsumerOffset offset) {
        SrsMeta m = require(miningChainId);
        List<ConsumerOffset> next = new ArrayList<>(m.consumerOffsets());
        next.removeIf(c -> c.pipelineId().equals(offset.pipelineId()));
        next.add(offset);
        records.put(miningChainId, new SrsMeta(
                m.miningChainId(), m.sourceReadOffset(), next, m.cdcStartPosition(),
                m.schemaHistory(), m.retention()));
    }

    @Override
    public synchronized void advanceConsumerReadSeq(
            String miningChainId, String pipelineId, String table, long lastReadSeq) {
        SrsMeta m = require(miningChainId);
        List<ConsumerOffset> next = new ArrayList<>();
        ConsumerOffset existing = null;
        for (ConsumerOffset c : m.consumerOffsets()) {
            if (c.pipelineId().equals(pipelineId)) {
                existing = c;
            } else {
                next.add(c);
            }
        }
        Map<String, Long> perTable = new LinkedHashMap<>(existing == null ? Map.of() : existing.perTableSeq());
        perTable.put(table, lastReadSeq);
        String ack = existing == null ? null : existing.sinkAckedSrcpos();
        next.add(new ConsumerOffset(pipelineId, perTable, ack));
        records.put(miningChainId, new SrsMeta(
                m.miningChainId(), m.sourceReadOffset(), next, m.cdcStartPosition(),
                m.schemaHistory(), m.retention()));
    }

    @Override
    public synchronized void advanceSinkAckedSrcpos(String miningChainId, String pipelineId, String srcpos) {
        SrsMeta m = require(miningChainId);
        List<ConsumerOffset> next = new ArrayList<>();
        ConsumerOffset existing = null;
        for (ConsumerOffset c : m.consumerOffsets()) {
            if (c.pipelineId().equals(pipelineId)) {
                existing = c;
            } else {
                next.add(c);
            }
        }
        Map<String, Long> perTable = existing == null ? Map.of() : existing.perTableSeq();
        next.add(new ConsumerOffset(pipelineId, perTable, srcpos));
        records.put(miningChainId, new SrsMeta(
                m.miningChainId(), m.sourceReadOffset(), next, m.cdcStartPosition(),
                m.schemaHistory(), m.retention()));
    }

    @Override
    public synchronized void setCdcStartPosition(String miningChainId, String cdcStartPosition) {
        SrsMeta m = require(miningChainId);
        records.put(miningChainId, new SrsMeta(
                m.miningChainId(), m.sourceReadOffset(), m.consumerOffsets(), cdcStartPosition,
                m.schemaHistory(), m.retention()));
    }

    @Override
    public synchronized void appendSchemaVersion(String miningChainId, SchemaVersion version) {
        SrsMeta m = require(miningChainId);
        List<SchemaVersion> next = new ArrayList<>(m.schemaHistory());
        next.add(version);
        records.put(miningChainId, new SrsMeta(
                m.miningChainId(), m.sourceReadOffset(), m.consumerOffsets(), m.cdcStartPosition(),
                next, m.retention()));
    }

    private SrsMeta require(String miningChainId) {
        SrsMeta m = records.get(miningChainId);
        if (m == null) {
            throw new IllegalStateException("mining chain not seeded: " + miningChainId);
        }
        return m;
    }
}
