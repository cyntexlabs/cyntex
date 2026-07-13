package io.cyntex.core.dsl;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;

/**
 * Compiles + type-checks the row expressions (CEL): the single place the cel-java dependency is
 * touched for compilation. It does not evaluate — evaluation is the runtime layer's concern — but
 * it serves both sides from one compiler environment: the validate layer takes the diagnostic
 * string ({@link #predicateError} / {@link #valueError}), the runtime layer takes the checked AST
 * it then evaluates ({@link #predicateAst} / {@link #valueAst}). Sharing the one environment is
 * what guarantees an expression that type-checks at validate time is the same one that evaluates at
 * runtime — the two can never drift.
 *
 * <p>Expressions bind the event envelope as their root: {@code op} / {@code ts} / {@code src} are
 * scalars, {@code before} / {@code after} / {@code schema} are maps. Record field types are unknown
 * offline (no schema), so member access such as {@code after.region} resolves to {@code dyn} — a
 * typo in a top-level envelope name is still caught, but field-level type errors are deferred to
 * the runtime. The function environment is the CEL standard library (with the standard macros
 * {@code has} / {@code exists} / {@code all} / {@code map} / {@code filter}) plus the one builtin
 * the grammar commits ({@code now()}). Two shapes: a predicate must compile to {@code bool}; a
 * computed value may compile to any type.
 */
public final class RowExpressions {

    private RowExpressions() {
    }

    /** {@code now()} — current event time; the one builtin beyond the CEL standard library. */
    private static final CelFunctionDecl NOW = CelFunctionDecl.newFunctionDeclaration(
            "now", CelOverloadDecl.newGlobalOverload("now_timestamp", SimpleType.TIMESTAMP));

    private static final CelCompiler PREDICATE = envelope().setResultType(SimpleType.BOOL).build();
    private static final CelCompiler VALUE = envelope().build();

    /** A compiler over the envelope root, with no result-type constraint. */
    private static CelCompilerBuilder envelope() {
        return CelCompilerFactory.standardCelCompilerBuilder()
                // the CEL standard macros (has / exists / all / map / filter) are off by default in
                // cel-java; has() in particular is the canonical presence test for the dyn-typed
                // envelope map fields, so enable the standard set explicitly.
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .addVar("op", SimpleType.STRING)
                .addVar("ts", SimpleType.INT)
                .addVar("src", SimpleType.STRING)
                .addVar("before", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("after", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("schema", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addFunctionDeclarations(NOW);
    }

    /** Checks a predicate; returns the diagnostic, or {@code null} when it compiles to {@code bool}. */
    static String predicateError(String expr) {
        return error(PREDICATE, expr);
    }

    /** Checks a computed value; returns the diagnostic, or {@code null} when it compiles. */
    static String valueError(String expr) {
        return error(VALUE, expr);
    }

    /**
     * Compiles a predicate to a checked AST for evaluation. The expression is expected to have
     * already passed validation, so a compile failure here is a programmer error (an unchecked
     * expression reaching the runtime), not a diagnosable user condition — it bare-throws.
     */
    public static CelAbstractSyntaxTree predicateAst(String expr) {
        return ast(PREDICATE, expr);
    }

    /** Compiles a computed value to a checked AST for evaluation; bare-throws like {@link #predicateAst}. */
    public static CelAbstractSyntaxTree valueAst(String expr) {
        return ast(VALUE, expr);
    }

    private static String error(CelCompiler compiler, String expr) {
        CelValidationResult result = compiler.compile(expr);
        return result.hasError() ? result.getErrorString() : null;
    }

    private static CelAbstractSyntaxTree ast(CelCompiler compiler, String expr) {
        CelValidationResult result = compiler.compile(expr);
        if (result.hasError()) {
            throw new IllegalArgumentException(result.getErrorString());
        }
        try {
            return result.getAst();
        } catch (CelValidationException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
