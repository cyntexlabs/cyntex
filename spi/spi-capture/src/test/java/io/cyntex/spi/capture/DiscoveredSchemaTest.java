package io.cyntex.spi.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveredSchemaTest {

    @Test
    void tablesDefaultToEmptyWhenNull() {
        assertThat(new DiscoveredSchema(null).tables()).isEmpty();
    }

    @Test
    void tablesAreADefensiveCopyAndUnmodifiable() {
        List<TableSchema> source = new ArrayList<>();
        source.add(new TableSchema("orders", List.of()));
        DiscoveredSchema schema = new DiscoveredSchema(source);

        source.add(new TableSchema("customers", List.of()));

        assertThat(schema.tables()).hasSize(1);
        assertThatThrownBy(() -> schema.tables().add(new TableSchema("customers", List.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void tableNameIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TableSchema(null, List.of()));
    }

    @Test
    void tableFieldsDefaultToEmptyAndAreUnmodifiable() {
        TableSchema table = new TableSchema("orders", null);

        assertThat(table.fields()).isEmpty();
        assertThatThrownBy(() -> table.fields().add(new FieldSchema("id", "long")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fieldNameIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new FieldSchema(null, "long"));
    }

    @Test
    void fieldTypeMayBeNullWhenDiscoveryCannotResolveIt() {
        FieldSchema field = new FieldSchema("payload", null);

        assertThat(field.name()).isEqualTo("payload");
        assertThat(field.type()).isNull();
    }
}
