package io.cyntex.adapters.pdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.spi.sink.TargetField;
import io.cyntex.spi.sink.TargetTable;
import io.tapdata.entity.schema.TapTable;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Builds a PDK {@link TapTable} descriptor from a resolved cyntex {@link TargetTable}: the target's
 * fields become typed columns and the primary-key fields become the key an upsert matches on, in field
 * order. This is the write-side model a connector reads to create the table and to coerce each row
 * value; the projection is exercised in isolation here, apart from any live connector.
 */
class TargetTapTableTest {

    @Test
    void carriesTheTargetNameAndEachFieldWithItsType() {
        TapTable table = TargetTapTable.build(new TargetTable("orders",
                List.of(new TargetField("id", "bigint", true), new TargetField("name", "varchar", false))));

        assertThat(table.getName()).isEqualTo("orders");
        assertThat(table.getNameFieldMap()).containsOnlyKeys("id", "name");
        assertThat(table.getNameFieldMap().get("id").getDataType()).isEqualTo("bigint");
        assertThat(table.getNameFieldMap().get("name").getDataType()).isEqualTo("varchar");
    }

    @Test
    void derivesThePrimaryKeyFromTheFlaggedFieldsInFieldOrder() {
        TapTable table = TargetTapTable.build(new TargetTable("orders", List.of(
                new TargetField("region", "varchar", true),
                new TargetField("payload", "text", false),
                new TargetField("id", "bigint", true))));

        // A composite key in field order: region before id, and the non-key column excluded.
        assertThat(table.primaryKeys()).containsExactly("region", "id");
    }

    @Test
    void hasNoPrimaryKeyWhenNoFieldIsFlagged() {
        TapTable table = TargetTapTable.build(new TargetTable("events",
                List.of(new TargetField("payload", "text", false))));

        assertThat(table.primaryKeys()).isEmpty();
    }

    @Test
    void anUnresolvedFieldTypePassesThroughAsNullForTheConnectorToInfer() {
        TapTable table = TargetTapTable.build(new TargetTable("orders",
                List.of(new TargetField("id", null, true))));

        assertThat(table.getNameFieldMap().get("id").getDataType()).isNull();
    }
}
