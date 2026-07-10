package io.cyntex.spi.store;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SrsMetaTest {

    private static ConsumerOffset consumer() {
        return new ConsumerOffset("orders-pipeline", Map.of("orders", 42L), "gtid:aaa-1:100");
    }

    private static SchemaVersion schema() {
        return new SchemaVersion(1L, Map.of("id", "long"), 0L);
    }

    @Test
    void holdsTheMiningChainMetaFields() {
        SrsMeta meta = new SrsMeta("chain-1", "gtid:aaa-1:120",
                List.of(consumer()), "gtid:aaa-1:0", List.of(schema()), "7d");
        assertThat(meta.miningChainId()).isEqualTo("chain-1");
        assertThat(meta.sourceReadOffset()).isEqualTo("gtid:aaa-1:120");
        assertThat(meta.consumerOffsets()).containsExactly(consumer());
        assertThat(meta.cdcStartPosition()).isEqualTo("gtid:aaa-1:0");
        assertThat(meta.schemaHistory()).containsExactly(schema());
        assertThat(meta.retention()).isEqualTo("7d");
    }

    @Test
    void allowsNullableOffsetsBeforeAnyCdcHasBeenRead() {
        // A freshly seeded mining chain: no source read offset yet, no cdc-start position, no retention
        // set, and no consumers or schema versions attached.
        SrsMeta meta = new SrsMeta("chain-1", null, List.of(), null, List.of(), null);
        assertThat(meta.sourceReadOffset()).isNull();
        assertThat(meta.cdcStartPosition()).isNull();
        assertThat(meta.retention()).isNull();
        assertThat(meta.consumerOffsets()).isEmpty();
        assertThat(meta.schemaHistory()).isEmpty();
    }

    @Test
    void copiesTheConsumerListSoALaterMutationDoesNotLeakIn() {
        List<ConsumerOffset> live = new ArrayList<>();
        live.add(consumer());
        SrsMeta meta = new SrsMeta("chain-1", null, live, null, List.of(), null);
        live.clear();
        assertThat(meta.consumerOffsets()).containsExactly(consumer());
    }

    @Test
    void copiesTheSchemaHistorySoALaterMutationDoesNotLeakIn() {
        List<SchemaVersion> live = new ArrayList<>();
        live.add(schema());
        SrsMeta meta = new SrsMeta("chain-1", null, List.of(), null, live, null);
        live.clear();
        assertThat(meta.schemaHistory()).containsExactly(schema());
    }

    @Test
    void rejectsMutationOfTheReturnedLists() {
        SrsMeta meta = new SrsMeta("chain-1", null, List.of(), null, List.of(), null);
        assertThatThrownBy(() -> meta.consumerOffsets().add(consumer()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> meta.schemaHistory().add(schema()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsABlankMiningChainId() {
        assertThatThrownBy(() -> new SrsMeta("  ", null, List.of(), null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullLists() {
        assertThatThrownBy(() -> new SrsMeta("chain-1", null, null, null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SrsMeta("chain-1", null, List.of(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
