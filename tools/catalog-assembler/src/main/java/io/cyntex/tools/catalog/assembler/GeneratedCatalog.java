package io.cyntex.tools.catalog.assembler;

import java.util.Map;

/**
 * The deterministic, ready-to-write output of a catalog generation: the index JSON, the per-connector
 * entry JSON keyed by id (index order), and the ingest report markdown. Pure content — writing it to
 * disk and byte-locking it is the caller's job.
 */
record GeneratedCatalog(String index, Map<String, String> entries, String report) {
}
