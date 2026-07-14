package io.cyntex.cli;

/**
 * A connector registration as the CLI renders it: the control ring's register report decoded off the
 * wire — the connector id and content hash the artifact was filed under, the PDK API version it declares
 * (null when it declares none), and whether this call newly registered it or found it already present.
 */
record RegisteredConnector(String connectorId, String contentHash, String pdkApiVersion, boolean newlyRegistered) {
}
