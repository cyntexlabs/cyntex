package io.cyntex.spi.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetTableTest {

    @Test
    void carriesNameAndFieldsInOrder() {
        TargetTable table = new TargetTable("orders",
                List.of(new TargetField("id", "int", true), new TargetField("v", "text", false)));

        assertThat(table.name()).isEqualTo("orders");
        assertThat(table.fields()).extracting(TargetField::name).containsExactly("id", "v");
    }

    @Test
    void nullFieldsBecomeEmpty() {
        assertThat(new TargetTable("orders", null).fields()).isEmpty();
    }

    @Test
    void fieldsAreAnUnmodifiableDefensiveCopy() {
        List<TargetField> source = new ArrayList<>();
        source.add(new TargetField("id", "int", true));
        TargetTable table = new TargetTable("orders", source);

        source.add(new TargetField("leak", "text", false)); // a later mutation of the caller's list must not leak in

        assertThat(table.fields()).hasSize(1);
        assertThatThrownBy(() -> table.fields().add(new TargetField("x", "int", false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresANonBlankName() {
        assertThatThrownBy(() -> new TargetTable(" ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
