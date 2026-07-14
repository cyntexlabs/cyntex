package io.cyntex.control.restapi;

import java.util.Map;

/**
 * The body of a discover-schema request: the stored connection to discover — its {@code id} (which is also
 * the key the discovered model is stored under), the {@code connectorId} it configures, and the
 * {@code settings} to run that connector with. A null {@code settings} is a connection with no configured
 * values.
 */
record ConnectionDiscoverSchemaRequest(String id, String connectorId, Map<String, Object> settings) {
}
