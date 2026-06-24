package io.cyntex.tools.catalog.assembler;

/**
 * A connector the walker resolved as ingestable: its declared id, the module directory it lives in,
 * the repo-root-relative path to its canonical spec.json, and the fully-qualified connector class
 * (null for a JavaScript connector, which has no Java class to classload). The class drives
 * capability derivation; everything else drives spec normalization.
 */
record ConnectorSource(String id, String moduleName, String specPath, String connectorClassFqn,
                       boolean javascript) {
}
