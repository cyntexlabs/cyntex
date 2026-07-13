package io.cyntex.control.core;

import io.cyntex.spi.store.DiscoveredSourceModel;
import io.cyntex.spi.store.SourceField;
import io.cyntex.spi.store.SourceIndex;
import io.cyntex.spi.store.SourceTable;

import java.util.List;

/**
 * The surface-facing report of a schema discovery: the control ring's own projection of the storage-port
 * {@link DiscoveredSourceModel} envelope, so the HTTP and CLI faces render a control-ring type and never
 * reach into the storage ports. It carries the discovered tables in discovery order — each with its
 * fields, primary key and indexes — and the time the model was discovered (epoch milliseconds). An
 * immutable value.
 */
public record SchemaReport(String connectionId, String connectorId, List<Table> tables, long discoveredAt) {

    /** Projects a storage-port discovery envelope onto the surface report. */
    public static SchemaReport from(DiscoveredSourceModel discovered) {
        return new SchemaReport(
                discovered.connectionId(),
                discovered.connectorId(),
                discovered.model().tables().stream().map(Table::from).toList(),
                discovered.discoveredAt());
    }

    /** One discovered table: its name, fields in discovery order, primary-key fields, and indexes. */
    public record Table(String name, List<Field> fields, List<String> primaryKey, List<Index> indexes) {

        static Table from(SourceTable table) {
            return new Table(
                    table.name(),
                    table.fields().stream().map(Field::from).toList(),
                    table.primaryKey(),
                    table.indexes().stream().map(Index::from).toList());
        }
    }

    /** One discovered field: its name and the connector-declared type, null when unresolved. */
    public record Field(String name, String type) {

        static Field from(SourceField field) {
            return new Field(field.name(), field.type());
        }
    }

    /** One discovered index: its name, the fields it spans, and whether it is unique. */
    public record Index(String name, List<String> fields, boolean unique) {

        static Index from(SourceIndex index) {
            return new Index(index.name(), index.fields(), index.unique());
        }
    }
}
