package io.cyntex.spi.sink;

/**
 * How a sink lands rows. A closed set of two.
 */
public enum WriteMode {

    /**
     * Every event lands as an insert: updates and deletes are reforged into inserts, an append-only
     * stream with no key lookup (the target needs no primary key).
     */
    APPEND,

    /** Rows are keyed: an insert or update lands by key, a delete removes by key. */
    UPSERT
}
