package io.cyntex.adapters.transform;

import java.io.Serializable;

/**
 * One projection rule of a {@code map}, in the serializable shape that travels to a member: an
 * output field keyed by name, plus how its value is produced. It mirrors the model's field rule but
 * carries no model type, so the supplier that captures a {@link MapSpec} serializes with primitives
 * only. The {@link Literal} value is whatever the authored literal was (a string / number / boolean
 * / nested collection), all of which serialize.
 */
sealed interface MapRule extends Serializable {

    /** The output field name this rule produces (or, for {@link Drop}, removes). */
    String output();

    /** {@code output: $source} — take the value of a source field; the source is consumed. */
    record Rename(String output, String source) implements MapRule {
    }

    /** {@code output: false} — remove the field from the projection. */
    record Drop(String output) implements MapRule {
    }

    /** {@code output: <literal>} — set a constant value. */
    record Literal(String output, Object value) implements MapRule {
    }

    /** {@code output: =<CEL>} — compute the value from a row expression. */
    record Computed(String output, String expr) implements MapRule {
    }
}
