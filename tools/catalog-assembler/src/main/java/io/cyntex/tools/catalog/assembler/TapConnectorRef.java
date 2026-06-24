package io.cyntex.tools.catalog.assembler;

/**
 * A connector's canonical spec file (as named by its {@code @TapConnectorClass} annotation) and the
 * fully-qualified name of the class that bears it (the class catalog-derive classloads to probe
 * capabilities).
 */
record TapConnectorRef(String specFile, String classFqn) {
}
