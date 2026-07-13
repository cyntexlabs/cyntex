package io.cyntex.core.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cel.common.CelAbstractSyntaxTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The public CEL compilation surface shared by the validate layer (diagnostic strings) and the
 * runtime evaluation layer (a checked AST the engine's transform ports run). One compiler
 * environment over the §6 envelope root serves both, so a predicate that type-checks at validate
 * time is the same one that evaluates at runtime — the two can never drift.
 */
class RowExpressionsTest {

    @Test
    @DisplayName("predicateAst compiles a bool row predicate to a checked AST")
    void predicateAstCompilesBoolPredicate() {
        CelAbstractSyntaxTree ast = RowExpressions.predicateAst("after.deleted == 0");

        assertThat(ast).isNotNull();
    }

    @Test
    @DisplayName("valueAst compiles a computed row value to a checked AST")
    void valueAstCompilesValue() {
        CelAbstractSyntaxTree ast = RowExpressions.valueAst("after.first + ' ' + after.last");

        assertThat(ast).isNotNull();
    }

    @Test
    @DisplayName("predicateAst refuses an expression that references an undeclared envelope field")
    void predicateAstRefusesUndeclaredField() {
        assertThatThrownBy(() -> RowExpressions.predicateAst("afterr.region == 'US'"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("predicateAst refuses a predicate that does not yield bool")
    void predicateAstRefusesNonBool() {
        assertThatThrownBy(() -> RowExpressions.predicateAst("src"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
