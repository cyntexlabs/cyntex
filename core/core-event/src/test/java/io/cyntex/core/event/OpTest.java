package io.cyntex.core.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpTest {

    @Test
    void isAClosedSetOfFive() {
        assertThat(Op.values()).containsExactly(Op.INSERT, Op.UPDATE, Op.DELETE, Op.READ, Op.DDL);
    }

    @Test
    void eachConstantHasItsWireSymbol() {
        assertThat(Op.INSERT.symbol()).isEqualTo("i");
        assertThat(Op.UPDATE.symbol()).isEqualTo("u");
        assertThat(Op.DELETE.symbol()).isEqualTo("d");
        assertThat(Op.READ.symbol()).isEqualTo("r");
        assertThat(Op.DDL.symbol()).isEqualTo("ddl");
    }

    @Test
    void fromSymbolRoundTripsEveryConstant() {
        for (Op op : Op.values()) {
            assertThat(Op.fromSymbol(op.symbol())).isEqualTo(op);
        }
    }

    @Test
    void fromSymbolRejectsAnUnknownSymbol() {
        assertThatThrownBy(() -> Op.fromSymbol("x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x");
    }
}
