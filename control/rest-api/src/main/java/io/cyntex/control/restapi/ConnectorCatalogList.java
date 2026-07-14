package io.cyntex.control.restapi;

import io.cyntex.control.core.ConnectorSummary;

import java.util.List;

/**
 * The connector-list response body: the connectors the online catalog view exposes — the bundled
 * snapshot union the rows derived for registered connectors — each tagged bundled or registered. A
 * wrapper object rather than a bare array so the response can grow fields without changing the wire shape.
 */
public record ConnectorCatalogList(List<ConnectorSummary> connectors) {
}
