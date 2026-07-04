package io.cyntex.spi.transform;

/**
 * The kinds of transform node a pipeline can carry. A closed set of six.
 *
 * <p>Three are stateless and row-level — they implement the {@link TransformPort} seam directly:
 * {@link #FILTER}, {@link #MAP} and {@link #JS}. Three are not: {@link #UNION} merges many input
 * streams into one, and {@link #NEST} / {@link #JOIN} accumulate state across events. The three
 * non-row-level kinds reserve their names here; their execution contracts land with the execution
 * engine.
 */
public enum NodeType {

    /** Drops events that fail a predicate; a stateless row-level transform. */
    FILTER,

    /** Reshapes each event's fields declaratively; a stateless row-level transform. */
    MAP,

    /** Reshapes each event with a script; a stateless row-level transform. */
    JS,

    /** Merges many input streams into one; a fan-in node, not a row-level transform. */
    UNION,

    /** Embeds related rows as a nested field, keeping state across events; a stateful node. */
    NEST,

    /** Joins two streams on a key, keeping state across events; a stateful node. */
    JOIN
}
