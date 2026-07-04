package io.cyntex.spi.sink;

/**
 * What a sink does with a schema-change ({@code ddl}) event. A closed set of three.
 */
public enum DdlPolicy {

    /** Apply the schema change to the target. */
    APPLY,

    /** Skip the schema change and keep writing rows. */
    IGNORE,

    /** Treat the schema change as an error and stop. */
    FAIL
}
