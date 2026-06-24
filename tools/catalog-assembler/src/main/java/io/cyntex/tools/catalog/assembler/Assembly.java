package io.cyntex.tools.catalog.assembler;

import java.util.List;

import io.cyntex.core.catalog.ConnectorCatalogEntry;

/**
 * The product of a catalog assembly: the catalog entries (in index order) and the ingest report that
 * accounts for every connector and every degradation.
 */
record Assembly(List<ConnectorCatalogEntry> entries, IngestReport report) {
}
