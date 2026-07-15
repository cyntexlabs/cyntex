package io.cyntex.e2e;

import io.cyntex.core.lifecycle.LifecycleVerb;
import io.cyntex.core.lifecycle.PipelineState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The executor turns a specification into calls on a tier binding. What it must get right is order
 * and honesty: provisioning strictly before data, data strictly before steps, and a wait that fails
 * loudly on timeout instead of sliding past.
 */
class E2eExecutorTest {

    private static final String PIPELINE_ID = "mongo2mongo";
    private static final TableAlias SOURCE = new TableAlias("src_mongo", "orders");
    private static final TableAlias TARGET = new TableAlias("tgt_mongo", "orders");

    private final RecordingBinding binding = new RecordingBinding();

    @Test
    void provisionsInDependencyOrderBeforeAnyDataOrSteps() {
        execute(
                """
                name: n
                tier: smoke
                setup:
                  connectors: [mongodb]
                  apply: [src_mongo.cyn.yml, tgt_mongo.cyn.yml]
                  discover: [src_mongo]
                pipeline: p.cyn.yml
                seed:
                  src_mongo.orders: { rows: 3 }
                steps:
                  - run
                """);

        assertThat(binding.calls)
                .containsExactly(
                        "register:mongodb",
                        "apply:src_mongo.cyn.yml",
                        "apply:tgt_mongo.cyn.yml",
                        "discover:src_mongo",
                        "seed:src_mongo.orders=3",
                        "drive:START");
    }

    @Test
    void drivesEveryLifecycleVerbInDeclarationOrder() {
        execute(minimal("steps:\n  - run\n  - pause\n  - resume\n  - stop\n"));

        assertThat(binding.calls)
                .containsExactly("drive:START", "drive:PAUSE", "drive:RESUME", "drive:STOP");
    }

    @Test
    void awaitPollsUntilTheMatcherHolds() {
        binding.countsOverTime(TARGET, 0L, 0L, 100L);

        execute(minimal("steps:\n  - run\n  - await: { count: { tgt_mongo.orders: 100 } }\n"));

        assertThat(binding.countReads).isEqualTo(3);
    }

    @Test
    void awaitFailsLoudlyWhenTheBoundExpires() {
        binding.countsOverTime(TARGET, 7L);

        assertThatThrownBy(
                        () -> execute(minimal("steps:\n  - await: { count: { tgt_mongo.orders: 100 } }\n")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tgt_mongo.orders")
                .hasMessageContaining("100")
                .hasMessageContaining("7");
    }

    @Test
    void assertChecksOnceAndDoesNotWait() {
        binding.countsOverTime(TARGET, 0L, 100L);

        assertThatThrownBy(
                        () -> execute(minimal("steps:\n  - assert: { count: { tgt_mongo.orders: 100 } }\n")))
                .isInstanceOf(AssertionError.class);
        assertThat(binding.countReads).isEqualTo(1);
    }

    @Test
    void awaitAndAssertReadTheSameMatcherVocabulary() {
        binding.countsOverTime(TARGET, 100L);

        execute(minimal("steps:\n  - await: { count: { tgt_mongo.orders: 100 } }\n"));
        execute(minimal("steps:\n  - assert: { count: { tgt_mongo.orders: 100 } }\n"));
    }

    @Test
    void awaitsTheStateMatcherAgainstThePublishedObservation() {
        binding.states(PipelineState.NEW, PipelineState.RUNNING);

        execute(minimal("steps:\n  - await: { state: { mongo2mongo: RUNNING } }\n"));

        assertThat(binding.stateReads).isEqualTo(2);
    }

    @Test
    void producesCdcChangesAgainstTheNamedTable() {
        execute(minimal("steps:\n  - cdc: { src_mongo.orders: insert 10 }\n"));

        assertThat(binding.calls).containsExactly("cdc:src_mongo.orders=INSERT x10");
    }

    @Test
    void countMatcherOverSeveralTablesHoldsOnlyWhenEveryTableMatches() {
        binding.count(TARGET, 100L);
        binding.count(SOURCE, 1L);

        assertThatThrownBy(
                        () ->
                                execute(
                                        minimal(
                                                "steps:\n  - assert: { count: { tgt_mongo.orders: 100,"
                                                        + " src_mongo.orders: 2 } }\n")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("src_mongo.orders");
    }

    @Test
    void drivesTheVerbAgainstThePipelineResolvedFromTheEnvelope() {
        execute(minimal("steps:\n  - run\n"));

        assertThat(binding.drivenPipelineIds).containsExactly(PIPELINE_ID);
    }

    private void execute(String yaml) {
        binding.calls.clear();
        new E2eExecutor(binding, path -> PIPELINE_ID, Duration.ofMillis(200), Duration.ofMillis(1))
                .execute(EnvelopeParser.parse(yaml));
    }

    private static String minimal(String body) {
        return "name: n\ntier: smoke\npipeline: p.cyn.yml\n" + body;
    }

    /** Records what the executor asked for, and can vary a reading over successive polls. */
    private static final class RecordingBinding implements TierBinding {

        private final List<String> calls = new ArrayList<>();
        private final List<String> drivenPipelineIds = new ArrayList<>();
        private final Map<TableAlias, List<Long>> countSeries = new HashMap<>();
        private final Map<TableAlias, AtomicInteger> countCursor = new HashMap<>();
        private List<PipelineState> stateSeries = List.of(PipelineState.RUNNING);
        private int stateReads;
        private int countReads;

        void count(TableAlias table, long rows) {
            countsOverTime(table, rows);
        }

        void countsOverTime(TableAlias table, Long... readings) {
            countSeries.put(table, List.of(readings));
            countCursor.put(table, new AtomicInteger());
        }

        void states(PipelineState... readings) {
            stateSeries = List.of(readings);
        }

        @Override
        public void registerConnector(String connectorId) {
            calls.add("register:" + connectorId);
        }

        @Override
        public void applyResource(String resourceFile) {
            calls.add("apply:" + resourceFile);
        }

        @Override
        public void discoverSchema(String resourceId) {
            calls.add("discover:" + resourceId);
        }

        @Override
        public void seed(TableAlias table, long rows) {
            calls.add("seed:" + table + "=" + rows);
        }

        @Override
        public void drive(String pipelineId, LifecycleVerb verb) {
            drivenPipelineIds.add(pipelineId);
            calls.add("drive:" + verb);
        }

        @Override
        public void cdc(TableAlias table, CdcOp op, int rows) {
            calls.add("cdc:" + table + "=" + op + " x" + rows);
        }

        @Override
        public long count(TableAlias table) {
            countReads++;
            List<Long> series = countSeries.getOrDefault(table, List.of(0L));
            int index = countCursor.computeIfAbsent(table, t -> new AtomicInteger()).getAndIncrement();
            return series.get(Math.min(index, series.size() - 1));
        }

        @Override
        public PipelineState state(String pipelineId) {
            int index = stateReads++;
            return stateSeries.get(Math.min(index, stateSeries.size() - 1));
        }
    }
}
