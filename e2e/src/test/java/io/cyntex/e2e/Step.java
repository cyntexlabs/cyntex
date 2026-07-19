package io.cyntex.e2e;

import io.cyntex.core.lifecycle.LifecycleVerb;

/**
 * One stage of a specification. Steps run in declaration order; the order is the scenario.
 *
 * <p>Lifecycle steps are spelled exactly as the product spells them - {@code start}, {@code pause},
 * {@code resume}, {@code stop} - and carry the product's own verb enum, so neither the word nor the
 * value can drift from what the product accepts. {@code run} is deliberately not a step: the
 * product already reserves it for a different meaning, apply-then-start.
 *
 * <p>There is no rewind step: re-snapshotting is the explicit {@code stop} then {@code start} pair,
 * which is exactly what the product's verb set offers.
 */
public sealed interface Step {

    /** Drives one lifecycle verb and returns once the intent is recorded, not once it converges. */
    record Lifecycle(LifecycleVerb verb) implements Step {}

    /** Produces changes against a seeded table while the pipeline is running. */
    record Cdc(TableAlias table, CdcOp op, long rows) implements Step {}

    /** Polls a matcher until it holds or the bound expires. */
    record Await(Matcher matcher) implements Step {}

    /** Checks a matcher once, now. */
    record Assertion(Matcher matcher) implements Step {}
}
