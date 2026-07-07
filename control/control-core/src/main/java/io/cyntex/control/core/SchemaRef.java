package io.cyntex.control.core;

/**
 * References into the shared {@code cyntex/v1} JSON Schema for an operation's request parameters and its
 * result. Each side is an opaque pointer string (e.g. a JSON-pointer into {@code $defs}); either side may
 * be {@code null} when the operation takes no parameters or returns nothing. The reference is stored, not
 * resolved here — a later consumer resolves it against the bundled schema.
 */
public record SchemaRef(String params, String result) {}
