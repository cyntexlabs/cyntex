package io.cyntex.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The discovered source model value types: the tables a source exposes, each with its fields, primary
 * key and indexes. Pure immutable values with defensive copies and no connector-framework types.
 */
class SourceModelTest {

    private static SourceField field(String name, String type) {
        return new SourceField(name, type);
    }

    @Test
    void carriesTablesEachWithFieldsPrimaryKeyAndIndexes() {
        SourceTable orders = new SourceTable(
                "orders",
                List.of(field("id", "int"), field("amount", "decimal")),
                List.of("id"),
                List.of(new SourceIndex("idx_amount", List.of("amount"), false)));
        SourceModel model = new SourceModel(List.of(orders));

        assertThat(model.tables()).extracting(SourceTable::name).containsExactly("orders");
        SourceTable table = model.tables().get(0);
        assertThat(table.fields()).extracting(SourceField::name).containsExactly("id", "amount");
        assertThat(table.fields()).extracting(SourceField::type).containsExactly("int", "decimal");
        assertThat(table.primaryKey()).containsExactly("id");
        assertThat(table.indexes()).extracting(SourceIndex::name).containsExactly("idx_amount");
        assertThat(table.indexes().get(0).fields()).containsExactly("amount");
        assertThat(table.indexes().get(0).unique()).isFalse();
    }

    @Test
    void nullTablesBecomeEmpty() {
        assertThat(new SourceModel(null).tables()).isEmpty();
    }

    @Test
    void nullFieldsPrimaryKeyAndIndexesBecomeEmpty() {
        SourceTable table = new SourceTable("t", null, null, null);

        assertThat(table.fields()).isEmpty();
        assertThat(table.primaryKey()).isEmpty();
        assertThat(table.indexes()).isEmpty();
    }

    @Test
    void collectionsAreUnmodifiableDefensiveCopies() {
        List<SourceTable> tables = new ArrayList<>();
        tables.add(new SourceTable("t", List.of(field("id", "int")), List.of("id"), List.of()));
        SourceModel model = new SourceModel(tables);

        tables.add(new SourceTable("late", List.of(), List.of(), List.of())); // a later mutation must not leak in

        assertThat(model.tables()).extracting(SourceTable::name).containsExactly("t");
        assertThatThrownBy(() -> model.tables().add(new SourceTable("x", List.of(), List.of(), List.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void tableFieldPrimaryKeyAndIndexCopiesAreUnmodifiable() {
        SourceTable table = new SourceTable(
                "t",
                new ArrayList<>(List.of(field("id", "int"))),
                new ArrayList<>(List.of("id")),
                new ArrayList<>(List.of(new SourceIndex("i", new ArrayList<>(List.of("id")), true))));

        assertThatThrownBy(() -> table.primaryKey().add("late")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> table.indexes().get(0).fields().add("late"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fieldTypeMayBeNullWhenDiscoveryCannotResolveIt() {
        assertThat(field("mystery", null).type()).isNull();
    }

    @Test
    void requiresTableFieldAndIndexNames() {
        assertThatNullPointerException().isThrownBy(() -> new SourceTable(null, List.of(), List.of(), List.of()));
        assertThatNullPointerException().isThrownBy(() -> new SourceField(null, "int"));
        assertThatNullPointerException().isThrownBy(() -> new SourceIndex(null, List.of(), false));
    }
}
