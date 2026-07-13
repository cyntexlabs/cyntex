package io.cyntex.cli;

import java.util.List;

/**
 * The CLI's view of a connection's discovered source model: the tables the source exposes — each with
 * its fields, primary key and indexes — and when the model was discovered (epoch milliseconds). The
 * response-side value the {@code discover-schema} and {@code schema} verbs decode from the server's
 * JSON. The CLI carries no shared control type (rule R6: it reaches the server over HTTP only), so this
 * mirrors the server's report shape independently. A field {@code type} is the connector's own declared
 * type string, {@code null} when discovery could not resolve it — rendered rather than interpreted.
 */
record ConnectionSchema(String connectionId, String connectorId, List<Table> tables, long discoveredAt) {

    /** One discovered table: its name, fields in discovery order, primary-key fields, and indexes. */
    record Table(String name, List<Field> fields, List<String> primaryKey, List<Index> indexes) {
    }

    /** One discovered field: its name and the connector-declared type ({@code null} when unresolved). */
    record Field(String name, String type) {
    }

    /** One discovered index: its name, the fields it spans, and whether it is unique. */
    record Index(String name, List<String> fields, boolean unique) {
    }
}
