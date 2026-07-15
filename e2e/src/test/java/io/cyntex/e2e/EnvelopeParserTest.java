package io.cyntex.e2e;

import io.cyntex.core.lifecycle.LifecycleVerb;
import io.cyntex.core.lifecycle.PipelineState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The envelope is a frozen authoring surface: once published it is a long-term commitment, and it
 * evolves by adding facets, never by breaking what already parses. These tests are what holds that
 * line, so they read the surface exactly as an author writes it.
 */
class EnvelopeParserTest {

    private static final String FIRST_EXAMPLE =
            """
            name: mongo-to-mongo-first-sync
            tier: smoke
            setup:
              connectors: [mongodb]
              apply: [src_mongo.cyn.yml, tgt_mongo.cyn.yml]
              discover: [src_mongo]
            pipeline: mongo2mongo.cyn.yml
            seed:
              src_mongo.orders: { rows: 100 }
            steps:
              - run
              - await: { count: { tgt_mongo.orders: 100 } }
              - cdc: { src_mongo.orders: insert 10 }
              - await: { count: { tgt_mongo.orders: 110 } }
              - stop
              - run
              - await: { count: { tgt_mongo.orders: 110 } }
            """;

    @Test
    void parsesTheFrozenFirstExample() {
        Envelope envelope = EnvelopeParser.parse(FIRST_EXAMPLE);

        assertThat(envelope.name()).isEqualTo("mongo-to-mongo-first-sync");
        assertThat(envelope.tier()).isEqualTo(Tier.SMOKE);
        assertThat(envelope.pipeline()).isEqualTo("mongo2mongo.cyn.yml");
        assertThat(envelope.setup().connectors()).containsExactly("mongodb");
        assertThat(envelope.setup().apply()).containsExactly("src_mongo.cyn.yml", "tgt_mongo.cyn.yml");
        assertThat(envelope.setup().discover()).containsExactly("src_mongo");
        assertThat(envelope.seed())
                .containsExactly(new Seed(new TableAlias("src_mongo", "orders"), 100L));
    }

    @Test
    void parsesTheStepSequenceInOrder() {
        Envelope envelope = EnvelopeParser.parse(FIRST_EXAMPLE);

        assertThat(envelope.steps())
                .containsExactly(
                        new Step.Lifecycle(LifecycleVerb.START),
                        new Step.Await(Matcher.count(new TableAlias("tgt_mongo", "orders"), 100L)),
                        new Step.Cdc(new TableAlias("src_mongo", "orders"), CdcOp.INSERT, 10),
                        new Step.Await(Matcher.count(new TableAlias("tgt_mongo", "orders"), 110L)),
                        new Step.Lifecycle(LifecycleVerb.STOP),
                        new Step.Lifecycle(LifecycleVerb.START),
                        new Step.Await(Matcher.count(new TableAlias("tgt_mongo", "orders"), 110L)));
    }

    @Test
    void mapsTheRunStepOntoTheStartVerb() {
        Envelope envelope = EnvelopeParser.parse(minimal("steps:\n  - run\n"));

        assertThat(envelope.steps()).containsExactly(new Step.Lifecycle(LifecycleVerb.START));
    }

    @Test
    void parsesEveryLifecycleStep() {
        Envelope envelope =
                EnvelopeParser.parse(minimal("steps:\n  - run\n  - pause\n  - resume\n  - stop\n"));

        assertThat(envelope.steps())
                .extracting(step -> ((Step.Lifecycle) step).verb())
                .containsExactly(
                        LifecycleVerb.START, LifecycleVerb.PAUSE, LifecycleVerb.RESUME, LifecycleVerb.STOP);
    }

    @Test
    void parsesTheStateMatcherAgainstTheProductLifecycleEnum() {
        Envelope envelope =
                EnvelopeParser.parse(minimal("steps:\n  - assert: { state: { p1: RUNNING } }\n"));

        assertThat(envelope.steps())
                .containsExactly(new Step.Assertion(Matcher.state("p1", PipelineState.RUNNING)));
    }

    @Test
    void acceptsAssertAndAwaitCarryingTheSameMatcherVocabulary() {
        Envelope awaited = EnvelopeParser.parse(minimal("steps:\n  - await: { count: { t.orders: 1 } }\n"));
        Envelope asserted = EnvelopeParser.parse(minimal("steps:\n  - assert: { count: { t.orders: 1 } }\n"));

        Matcher same = Matcher.count(new TableAlias("t", "orders"), 1L);
        assertThat(awaited.steps()).containsExactly(new Step.Await(same));
        assertThat(asserted.steps()).containsExactly(new Step.Assertion(same));
    }

    @Test
    void rejectsAnUnknownTopLevelKey() {
        assertThatThrownBy(() -> EnvelopeParser.parse(minimal("expect: { count: { t.orders: 1 } }\n")))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("expect");
    }

    @Test
    void rejectsAnUnknownStepVerb() {
        assertThatThrownBy(() -> EnvelopeParser.parse(minimal("steps:\n  - restart\n")))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("restart");
    }

    @Test
    void rejectsAnUnknownMatcherWord() {
        assertThatThrownBy(() -> EnvelopeParser.parse(minimal("steps:\n  - await: { synced: true }\n")))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("synced");
    }

    @Test
    void rejectsAnAliasThatIsNotResourceIdDotTable() {
        assertThatThrownBy(() -> EnvelopeParser.parse(minimal("steps:\n  - await: { count: { orders: 1 } }\n")))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("orders");
    }

    @Test
    void rejectsAnUnknownTier() {
        assertThatThrownBy(() -> EnvelopeParser.parse("name: n\ntier: nightly\npipeline: p.cyn.yml\nsteps:\n  - run\n"))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("nightly");
    }

    @Test
    void rejectsAnEnvelopeWithoutAName() {
        assertThatThrownBy(() -> EnvelopeParser.parse("tier: smoke\npipeline: p.cyn.yml\nsteps:\n  - run\n"))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsAnEnvelopeWithoutAPipeline() {
        assertThatThrownBy(() -> EnvelopeParser.parse("name: n\ntier: smoke\nsteps:\n  - run\n"))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("pipeline");
    }

    @Test
    void rejectsAnUnknownCdcOperation() {
        assertThatThrownBy(() -> EnvelopeParser.parse(minimal("steps:\n  - cdc: { t.orders: truncate 1 }\n")))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("truncate");
    }

    @Test
    void readsTheTierVocabularyInFull() {
        assertThat(EnvelopeParser.parse(tier("smoke")).tier()).isEqualTo(Tier.SMOKE);
        assertThat(EnvelopeParser.parse(tier("full")).tier()).isEqualTo(Tier.FULL);
        assertThat(EnvelopeParser.parse(tier("perf")).tier()).isEqualTo(Tier.PERF);
    }

    @Test
    void treatsSetupAsOptionalSoAPipelineOnlyCaseStillParses() {
        Envelope envelope = EnvelopeParser.parse(minimal("steps:\n  - run\n"));

        assertThat(envelope.setup().connectors()).isEmpty();
        assertThat(envelope.setup().apply()).isEmpty();
        assertThat(envelope.setup().discover()).isEmpty();
        assertThat(envelope.seed()).isEmpty();
    }

    @Test
    void keepsSeedEntriesInDeclarationOrder() {
        Envelope envelope =
                EnvelopeParser.parse(
                        minimal("seed:\n  a.t1: { rows: 1 }\n  b.t2: { rows: 2 }\nsteps:\n  - run\n"));

        assertThat(envelope.seed())
                .containsExactly(
                        new Seed(new TableAlias("a", "t1"), 1L), new Seed(new TableAlias("b", "t2"), 2L));
    }

    @Test
    void rejectsMalformedYaml() {
        assertThatThrownBy(() -> EnvelopeParser.parse("name: [unclosed\n"))
                .isInstanceOf(EnvelopeException.class);
    }

    private static String minimal(String body) {
        return "name: n\ntier: smoke\npipeline: p.cyn.yml\n" + body;
    }

    private static String tier(String tier) {
        return "name: n\ntier: " + tier + "\npipeline: p.cyn.yml\nsteps:\n  - run\n";
    }

    @Test
    void exposesTheStepsInAStableList() {
        Envelope envelope = EnvelopeParser.parse(FIRST_EXAMPLE);

        assertThat(envelope.steps()).hasSize(7);
        assertThat(List.copyOf(envelope.steps())).isEqualTo(envelope.steps());
    }
}
