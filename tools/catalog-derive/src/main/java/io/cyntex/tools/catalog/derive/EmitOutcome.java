package io.cyntex.tools.catalog.derive;

import java.util.Map;
import java.util.Set;

/**
 * The result of a derive run: the capability bitmap for the connectors that probed, and the
 * connectors that were skipped, each mapped to why (no built jar, or a probe failure with its root
 * cause). The skips are surfaced so a refresh never loses a connector silently.
 */
record EmitOutcome(Map<String, Set<String>> bitmap, Map<String, String> skipped) {
}
