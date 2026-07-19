package io.cyntex.app;

import com.hazelcast.core.HazelcastInstance;
import io.cyntex.runtime.engine.SinkAck;
import io.cyntex.runtime.engine.SinkAckFactory;
import io.cyntex.runtime.srs.CaptureRunUnit;
import io.cyntex.spi.store.SrsMetaStore;
import java.util.Map;

/**
 * The production sink-ack factory carried onto the DAG: it advances one consumer pipeline's durable
 * sink-acked source position as the sink confirms writes, so the source-read durable frontier has a real
 * input. It holds only serializable coordinates — a {@code table -> mining chain id} map for every source
 * the pipeline reads, plus the consumer pipeline id — and resolves the durable store on the member that
 * runs the sink, mirroring how the source's read-cursor publisher binds its store member-side. The store
 * itself is not serializable and never crosses the wire.
 *
 * <p>The sink knows a chain only by the {@code src} stream name its events carry — a table at L1 — so this
 * maps that stream to the mining chain that keys its durable record and advances
 * {@code (miningChainId, pipelineId, srcpos)}. A member with no store bound resolves to a no-op ack, so a
 * sink still runs before the assembly layer makes the member SRS-capable. A stream the map does not carry
 * is a builder-side wiring defect (the sink saw a chain the pipeline never sourced) and crashes bare.
 */
final class StoreBackedSinkAckFactory implements SinkAckFactory {

    private static final long serialVersionUID = 1L;

    private final Map<String, String> chainIdByTable;
    private final String pipelineId;

    StoreBackedSinkAckFactory(Map<String, String> chainIdByTable, String pipelineId) {
        this.chainIdByTable = Map.copyOf(chainIdByTable);
        this.pipelineId = pipelineId;
    }

    @Override
    public SinkAck resolve(HazelcastInstance member) {
        Object bound = member.getUserContext().get(CaptureRunUnit.SRS_META_USER_CONTEXT_KEY);
        if (!(bound instanceof SrsMetaStore meta)) {
            return (chain, srcpos) -> { };
        }
        return (chain, srcpos) -> {
            String miningChainId = chainIdByTable.get(chain);
            if (miningChainId == null) {
                throw new IllegalStateException(
                        "sink acked a chain the pipeline never sourced: '" + chain + "'");
            }
            meta.advanceSinkAckedSrcpos(miningChainId, pipelineId, srcpos);
        };
    }
}
