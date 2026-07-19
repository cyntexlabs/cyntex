package io.cyntex.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hazelcast.core.HazelcastInstance;
import io.cyntex.runtime.engine.SinkAck;
import io.cyntex.runtime.srs.CaptureRunUnit;
import io.cyntex.spi.store.ConsumerOffset;
import io.cyntex.spi.store.SrsMetaStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;

/**
 * The production sink-ack factory maps a sink's chain (the {@code src} stream name, a table at L1) to its
 * mining chain and the consumer pipeline, resolves the durable store from the member it runs on, and
 * advances that consumer's durable sink-acked position. It ships only serializable coordinates and binds
 * the store member-side, so nothing store-bound crosses the wire.
 */
class StoreBackedSinkAckFactoryTest {

    @Test
    void advancesTheDurableSinkAckedPositionForTheChainThatMapsToTheTable() {
        InMemorySrsMetaStore store = new InMemorySrsMetaStore();
        store.create("mc-orders", null);
        store.create("mc-items", null);
        HazelcastInstance member = memberWith(store);

        SinkAck ack = new StoreBackedSinkAckFactory(
                Map.of("orders", "mc-orders", "items", "mc-items"), "pipe-1").resolve(member);

        ack.advance("orders", "w7");
        ack.advance("items", "w3");

        assertThat(ackedPosition(store, "mc-orders", "pipe-1")).isEqualTo("w7");
        assertThat(ackedPosition(store, "mc-items", "pipe-1")).isEqualTo("w3");
    }

    @Test
    void resolvesToANoOpWhenNoStoreIsBoundOnTheMember() {
        HazelcastInstance member = mock(HazelcastInstance.class);
        when(member.getUserContext()).thenReturn(new ConcurrentHashMap<>());

        SinkAck ack = new StoreBackedSinkAckFactory(Map.of("orders", "mc-orders"), "pipe-1").resolve(member);

        // A member the assembly layer has not made SRS-capable resolves to a no-op ack rather than failing,
        // mirroring the read-cursor publisher; a sink still runs before the store is bound.
        assertThat(catchThrowable(() -> ack.advance("orders", "w1"))).isNull();
    }

    @Test
    void aChainWithNoMappedTableIsAnInvariantViolation() {
        InMemorySrsMetaStore store = new InMemorySrsMetaStore();
        HazelcastInstance member = memberWith(store);

        SinkAck ack = new StoreBackedSinkAckFactory(Map.of("orders", "mc-orders"), "pipe-1").resolve(member);

        // The sink advances a chain the pipeline never sourced: a builder-side wiring defect, surfaced bare.
        assertThatThrownBy(() -> ack.advance("unknown_table", "w1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown_table");
    }

    private static HazelcastInstance memberWith(SrsMetaStore store) {
        ConcurrentMap<String, Object> context = new ConcurrentHashMap<>();
        context.put(CaptureRunUnit.SRS_META_USER_CONTEXT_KEY, store);
        HazelcastInstance member = mock(HazelcastInstance.class);
        when(member.getUserContext()).thenReturn(context);
        return member;
    }

    private static String ackedPosition(SrsMetaStore store, String chainId, String pipelineId) {
        return store.read(chainId).orElseThrow().consumerOffsets().stream()
                .filter(offset -> offset.pipelineId().equals(pipelineId))
                .map(ConsumerOffset::sinkAckedSrcpos)
                .findFirst()
                .orElse(null);
    }
}
