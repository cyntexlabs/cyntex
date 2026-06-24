package io.cyntex.tools.catalog.derive;

/**
 * One Java connector in the probe manifest the PDK-free assembler hands to catalog-derive: the id
 * (the bitmap key), the module (which resolves to the built dist jar) and the fully-qualified
 * connector class to classload. The field set mirrors what the assembler's manifest writer emits.
 */
record ManifestEntry(String id, String module, String connectorClass) {
}
