package io.cyntex.core.dsl;

import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;

/**
 * Compiles and type-checks §12 ② row expressions (CEL) at validate time — it never evaluates them
 * (evaluation is the engine's concern, out of the offline DSL layer). The single place the cel-java
 * dependency is touched.
 *
 * <p>Expressions bind the §6 event envelope as their root: {@code op} / {@code ts} / {@code src}
 * are scalars, {@code before} / {@code after} / {@code schema} are maps. Record field types are
 * unknown offline (no schema), so member access such as {@code after.region} resolves to
 * {@code dyn} — a typo in a top-level envelope name is still caught, but field-level type errors
 * are deferred to the engine. The function environment is the CEL standard library plus the one
 * builtin the grammar commits ({@code now()}); the CEL standard library here includes the standard
 * macros ({@code has} / {@code exists} / {@code all} / {@code map} / {@code filter}). It widens as
 * the expression dialect is pinned down.
 *
 * <p>Two shapes: a {@code filter} predicate must compile to {@code bool}; a {@code map} / push
 * computed value may compile to any type. Each entry point returns the compiler diagnostic string,
 * or {@code null} when the expression is well-formed.
 */
final class CelChecker {

    private CelChecker() {
    }

    /** {@code now()} — current event time; the one builtin §12 commits beyond the CEL standard library. */
    private static final CelFunctionDecl NOW = CelFunctionDecl.newFunctionDeclaration(
            "now", CelOverloadDecl.newGlobalOverload("now_timestamp", SimpleType.TIMESTAMP));

    private static final CelCompiler PREDICATE = envelope().setResultType(SimpleType.BOOL).build();
    private static final CelCompiler VALUE = envelope().build();

    /** A compiler over the §6 envelope root, with no result-type constraint. */
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

    /** Checks a {@code filter} predicate; returns the diagnostic, or {@code null} when it compiles to {@code bool}. */
    static String predicateError(String expr) {
        return error(PREDICATE, expr);
    }

    /** Checks a computed value; returns the diagnostic, or {@code null} when it compiles. */
    static String valueError(String expr) {
        return error(VALUE, expr);
    }

    private static String error(CelCompiler compiler, String expr) {
        CelValidationResult result = compiler.compile(expr);
        return result.hasError() ? result.getErrorString() : null;
    }
}
