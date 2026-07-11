package io.cyntex.adapters.pdk;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.SchemaDiscoverer;
import io.cyntex.spi.store.SourceField;
import io.cyntex.spi.store.SourceIndex;
import io.cyntex.spi.store.SourceModel;
import io.cyntex.spi.store.SourceTable;
import io.tapdata.entity.schema.TapIndex;
import io.tapdata.entity.schema.TapIndexField;
import io.tapdata.entity.schema.TapTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The PDK implementation of the schema-discovery port: it provisions a connector, refuses it with a
 * code if it will not load or its declared API level is incompatible, drives the connector's frozen
 * {@code discoverSchema} to enumerate the source's streams, and normalizes each PDK table into a
 * {@link SourceTable} — its fields, primary key and indexes. The connector is inited once for the
 * discovery and stopped afterwards; the PDK types stay inside this class and neither the port nor the
 * model carries any of them.
 *
 * <p>A connector that throws out of {@code discoverSchema} could not complete discovery — a coded
 * connector-domain failure, distinct from an empty source. A field's type is carried through exactly as
 * the connector reported it; mapping it onto the cyntex type namespace is a separate step not done
 * here. An un-loadable or level-incompatible connector is refused up front by the shared open path,
 * never downgraded into an empty model.
 */
public final class PdkSchemaDiscoverer implements SchemaDiscoverer {

    private final ConnectorProvisioner provisioner;

    public PdkSchemaDiscoverer(ConnectorProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public SourceModel discover(ConnectionConfig config) {
        PdkConnector connector = PdkConnector.open(
                config.connectorId(), provisioner.resolve(config.connectorId()), config.settings());
        try {
            return toSourceModel(drive(connector));
        } finally {
            connector.stopQuietly();
            connector.close();
        }
    }

    /**
     * Inits the connector once and drives its discoverSchema over every stream (an empty stream list =
     * all), collecting the reported tables. A connector that throws out of discovery could not complete
     * it — a coded connector-domain failure.
     */
    private static List<TapTable> drive(PdkConnector connector) {
        try {
            return connector.underLoader(() -> {
                connector.connector().init(connector.context());
                List<TapTable> tables = new ArrayList<>();
                connector.connector().discoverSchema(connector.context(), List.of(), Integer.MAX_VALUE, tables::addAll);
                return tables;
            });
        } catch (CyntexException e) {
            throw e;
        } catch (Throwable t) {
            throw new CyntexException(ConnectorError.DISCOVER_FAILED,
                    Map.of("connector", connector.connectorId(), "detail", detail(t)), t);
        }
    }

    private static SourceModel toSourceModel(List<TapTable> tables) {
        List<SourceTable> mapped = new ArrayList<>(tables.size());
        for (TapTable table : tables) {
            mapped.add(new SourceTable(table.getId(), fields(table), primaryKey(table), indexes(table)));
        }
        return new SourceModel(mapped);
    }

    private static List<SourceField> fields(TapTable table) {
        List<SourceField> fields = new ArrayList<>();
        if (table.getNameFieldMap() != null) {
            table.getNameFieldMap().forEach((name, field) -> fields.add(new SourceField(name, field.getDataType())));
        }
        return fields;
    }

    /** The primary-key column names in key order, as the table derives them from its fields. */
    private static List<String> primaryKey(TapTable table) {
        return new ArrayList<>(table.primaryKeys());
    }

    private static List<SourceIndex> indexes(TapTable table) {
        List<SourceIndex> indexes = new ArrayList<>();
        if (table.getIndexList() != null) {
            for (TapIndex index : table.getIndexList()) {
                indexes.add(new SourceIndex(index.getName(), indexFieldNames(index), Boolean.TRUE.equals(index.getUnique())));
            }
        }
        return indexes;
    }

    private static List<String> indexFieldNames(TapIndex index) {
        List<String> names = new ArrayList<>();
        if (index.getIndexFields() != null) {
            for (TapIndexField field : index.getIndexFields()) {
                names.add(field.getName());
            }
        }
        return names;
    }

    private static String detail(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
