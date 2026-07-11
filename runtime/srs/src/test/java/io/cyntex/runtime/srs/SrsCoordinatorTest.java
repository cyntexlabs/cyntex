package io.cyntex.runtime.srs;

import io.cyntex.spi.store.ConsumerOffset;
import io.cyntex.spi.store.SchemaVersion;
import io.cyntex.spi.store.SrsMeta;
import io.cyntex.spi.store.SrsMetaStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single-node SRS coordinator: it force-merges cdc sources onto shared mining chains (a chain is opened
 * once, then every same-config source joins and unions its tables) and enforces the SRS lifecycle boundary
 * — a source opens a chain independent of any pipeline, a pipeline attaches and detaches only its own
 * consumer membership without ever touching the shared chain, and tearing a chain down is a separate
 * explicit act that first lists the consumer pipelines it would affect. The durable per-consumer read
 * cursor is written later, when the run unit is wired; here the coordinator owns identity, membership and
 * the boundary.
 */
class SrsCoordinatorTest {

    private static final MiningChainId CHAIN = MiningChainId.ofKey("orders-db");

    // ---- provision + forced merge ------------------------------------------------

    @Test
    void provisioningANewChainSeedsItsMetaOnce() {
        FakeMeta meta = new FakeMeta();
        SrsCoordinator coord = new SrsCoordinator(meta);

        ProvisionOutcome out = coord.provisionSource("src-a", CHAIN, List.of("orders"), "7d");

        assertThat(out.merged()).isFalse();
        assertThat(out.chainId()).isEqualTo(CHAIN);
        assertThat(out.tables()).containsExactlyInAnyOrder("orders");
        assertThat(meta.created).containsEntry(CHAIN.value(), "7d");
        assertThat(coord.isProvisioned(CHAIN)).isTrue();
    }

    @Test
    void provisioningASecondSourceOnTheSameChainForceMergesWithoutReseeding() {
        FakeMeta meta = new FakeMeta();
        SrsCoordinator coord = new SrsCoordinator(meta);
        coord.provisionSource("src-a", CHAIN, List.of("orders"), "7d");
        int mutationsAfterFirst = meta.mutations.size();

        ProvisionOutcome out = coord.provisionSource("src-b", CHAIN, List.of("customers", "orders"), "30d");

        // The chain is already open: no second seed (create is insert-only), the joining source just unions.
        assertThat(out.merged()).isTrue();
        assertThat(meta.mutations).hasSize(mutationsAfterFirst);
        assertThat(out.tables()).containsExactlyInAnyOrder("orders", "customers");
        assertThat(coord.tablesOf(CHAIN)).containsExactlyInAnyOrder("orders", "customers");
        // Both sources are recorded on the one chain -- the force-merge, observable.
        assertThat(coord.sourcesOf(CHAIN)).containsExactlyInAnyOrder("src-a", "src-b");
    }

    @Test
    void aSourceOpensItsChainEvenWithNoPipelineConsuming() {
        FakeMeta meta = new FakeMeta();
        SrsCoordinator coord = new SrsCoordinator(meta);

        // Provision is bound to the source run-unit, not to a pipeline: the chain opens unconsumed.
        coord.provisionSource("src-a", CHAIN, List.of("orders"), null);

        assertThat(coord.isProvisioned(CHAIN)).isTrue();
    }

    // ---- consumer attach / detach boundary ---------------------------------------

    @Test
    void attachingConsumersTracksEachPipelineDistinctlyWithoutTouchingMeta() {
        FakeMeta meta = new FakeMeta();
        SrsCoordinator coord = new SrsCoordinator(meta);
        coord.provisionSource("src-a", CHAIN, List.of("orders"), "7d");
        int mutationsAfterProvision = meta.mutations.size();

        coord.attachConsumer(CHAIN, "p1");
        coord.attachConsumer(CHAIN, "p2");

        assertThat(coord.affectedConsumers(CHAIN)).containsExactlyInAnyOrder("p1", "p2");
        // Membership only at this stage: the durable per-consumer cursor is published when the run unit is wired.
        assertThat(meta.mutations).hasSize(mutationsAfterProvision);
    }

    @Test
    void attachingAConsumerToAnUnopenedChainIsAnOrderingError() {
        SrsCoordinator coord = new SrsCoordinator(new FakeMeta());

        // A pipeline can only consume a chain its source has already opened.
        assertThatThrownBy(() -> coord.attachConsumer(CHAIN, "p1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void detachingAConsumerTouchesOnlyItsOwnMembership() {
        FakeMeta meta = new FakeMeta();
        SrsCoordinator coord = new SrsCoordinator(meta);
        coord.provisionSource("src-a", CHAIN, List.of("orders", "customers"), "7d");
        coord.attachConsumer(CHAIN, "p1");
        coord.attachConsumer(CHAIN, "p2");
        int mutationsBeforeDetach = meta.mutations.size();

        coord.detachConsumer(CHAIN, "p1");

        // Only p1 is gone; the shared chain -- its other consumer, its tables, its durable meta -- is untouched.
        assertThat(coord.affectedConsumers(CHAIN)).containsExactly("p2");
        assertThat(coord.isProvisioned(CHAIN)).isTrue();
        assertThat(coord.tablesOf(CHAIN)).containsExactlyInAnyOrder("orders", "customers");
        assertThat(meta.mutations).hasSize(mutationsBeforeDetach);
    }

    @Test
    void detachingANonConsumerIsANoOp() {
        FakeMeta meta = new FakeMeta();
        SrsCoordinator coord = new SrsCoordinator(meta);
        coord.provisionSource("src-a", CHAIN, List.of("orders"), "7d");
        coord.attachConsumer(CHAIN, "p1");

        // A pipeline stop that had not started consuming clears nothing.
        coord.detachConsumer(CHAIN, "never-attached");

        assertThat(coord.affectedConsumers(CHAIN)).containsExactly("p1");
    }

    @Test
    void detachingTheLastConsumerLeavesTheChainOpen() {
        SrsCoordinator coord = new SrsCoordinator(new FakeMeta());
        coord.provisionSource("src-a", CHAIN, List.of("orders"), "7d");
        coord.attachConsumer(CHAIN, "p1");

        coord.detachConsumer(CHAIN, "p1");

        // The chain's lifecycle is the source's, not its consumers': an empty chain stays open, still mining.
        assertThat(coord.affectedConsumers(CHAIN)).isEmpty();
        assertThat(coord.isProvisioned(CHAIN)).isTrue();
    }

    // ---- source teardown: never implicit, lists affected first -------------------

    @Test
    void planningATeardownListsAffectedConsumersAndRingsWithoutChangingAnything() {
        FakeMeta meta = new FakeMeta();
        SrsCoordinator coord = new SrsCoordinator(meta);
        coord.provisionSource("src-a", CHAIN, List.of("orders", "customers"), "7d");
        coord.attachConsumer(CHAIN, "p1");
        coord.attachConsumer(CHAIN, "p2");
        int mutationsBefore = meta.mutations.size();

        SourceTeardownPlan plan = coord.planSourceTeardown(CHAIN);

        assertThat(plan.affectedConsumers()).containsExactlyInAnyOrder("p1", "p2");
        assertThat(plan.ringNames()).containsExactlyInAnyOrder(
                SrsRingbuffer.ringName(CHAIN.value(), "orders"),
                SrsRingbuffer.ringName(CHAIN.value(), "customers"));
        // Planning is read-only: the chain, its consumers and its durable meta are all still there.
        assertThat(coord.isProvisioned(CHAIN)).isTrue();
        assertThat(coord.affectedConsumers(CHAIN)).containsExactlyInAnyOrder("p1", "p2");
        assertThat(meta.mutations).hasSize(mutationsBefore);
    }

    @Test
    void tearingDownASourceClosesTheChainAndIsReachedOnlyByAnExplicitCall() {
        SrsCoordinator coord = new SrsCoordinator(new FakeMeta());
        coord.provisionSource("src-a", CHAIN, List.of("orders"), "7d");
        coord.attachConsumer(CHAIN, "p1");

        // A detach never reaches here (see detachingTheLastConsumerLeavesTheChainOpen); only teardown closes it.
        coord.teardownSource(CHAIN);

        assertThat(coord.isProvisioned(CHAIN)).isFalse();
    }

    @Test
    void planningOrTearingDownAnUnopenedChainIsAnOrderingError() {
        SrsCoordinator coord = new SrsCoordinator(new FakeMeta());

        assertThatThrownBy(() -> coord.planSourceTeardown(CHAIN)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> coord.teardownSource(CHAIN)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * An in-memory {@link SrsMetaStore} that records every mutation. {@code created} maps a seeded chain to
     * its retention; {@code mutations} is the ordered log used to assert a step touched — or did not touch —
     * the durable store. {@code create} is insert-only, matching the contract.
     */
    private static final class FakeMeta implements SrsMetaStore {
        final Map<String, String> created = new LinkedHashMap<>();
        final Map<String, SrsMeta> records = new LinkedHashMap<>();
        final List<String> mutations = new ArrayList<>();

        @Override
        public Optional<SrsMeta> read(String miningChainId) {
            return Optional.ofNullable(records.get(miningChainId));
        }

        @Override
        public void create(String miningChainId, String retention) {
            if (records.containsKey(miningChainId)) {
                throw new IllegalStateException("chain already seeded: " + miningChainId);
            }
            created.put(miningChainId, retention);
            records.put(miningChainId, new SrsMeta(miningChainId, null, List.of(), null, List.of(), retention));
            mutations.add("create:" + miningChainId);
        }

        @Override
        public void advanceSourceReadOffset(String miningChainId, String sourceReadOffset) {
            mutations.add("advance:" + miningChainId);
        }

        @Override
        public void upsertConsumerOffset(String miningChainId, ConsumerOffset offset) {
            mutations.add("upsert:" + miningChainId + ":" + offset.pipelineId());
        }

        @Override
        public void advanceConsumerReadSeq(String miningChainId, String pipelineId, String table, long lastReadSeq) {
            mutations.add("readSeq:" + miningChainId + ":" + pipelineId + ":" + table);
        }

        @Override
        public void setCdcStartPosition(String miningChainId, String cdcStartPosition) {
            mutations.add("cdcStart:" + miningChainId);
        }

        @Override
        public void appendSchemaVersion(String miningChainId, SchemaVersion version) {
            mutations.add("schema:" + miningChainId);
        }
    }
}
