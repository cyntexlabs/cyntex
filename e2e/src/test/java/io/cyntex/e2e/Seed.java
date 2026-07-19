package io.cyntex.e2e;

/** Initial data laid down on one table before the first step runs. */
public record Seed(TableAlias table, long rows) {}
