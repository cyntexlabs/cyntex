package io.cyntex.adapters.pdk;

import io.cyntex.spi.sink.TargetField;
import io.cyntex.spi.sink.TargetTable;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapTable;

/**
 * Builds a PDK {@link TapTable} descriptor from a resolved cyntex {@link TargetTable}: each target
 * field becomes a typed column, and the primary-key fields become the key an upsert matches on, in
 * field order. The descriptor is what a connector reads to create the table and to coerce each row
 * value to its column type.
 *
 * <p>A key field is given a primary-key position in field order, which both marks it as a key and
 * fixes the key's column order — set through the position, never the flag alone, so the derived key is
 * ordered rather than lost.
 */
final class TargetTapTable {

    private TargetTapTable() {
    }

    static TapTable build(TargetTable target) {
        TapTable table = new TapTable(target.name());
        int keyPos = 1;
        for (TargetField field : target.fields()) {
            TapField column = new TapField(field.name(), field.type());
            if (field.primaryKey()) {
                column.primaryKeyPos(keyPos++);
            }
            table.add(column);
        }
        return table;
    }
}
