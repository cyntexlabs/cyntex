package io.cyntex.adapters.transform;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import io.cyntex.core.dsl.RowExpressions;
import io.cyntex.core.event.Envelope;
import java.util.HashMap;
import java.util.Map;

/**
 * One compiled row expression, ready to evaluate against an event. The compiler environment is the
 * validate layer's — the AST comes from {@link RowExpressions}, so what type-checks at validate
 * time is exactly what runs here. The program is built once (member-side, from the serializable
 * expression text) and evaluated per event; a compiled program is immutable and reused.
 *
 * <p>Evaluation binds the envelope as the expression root the same way the compiler declared it:
 * {@code op} as its wire symbol, {@code ts} / {@code src} as scalars, and {@code before} / {@code
 * after} / {@code schema} as maps (an absent map binds empty, so a present-field test is well
 * defined rather than a null dereference).
 */
final class RowExpressionProgram {

    // A standard runtime with no custom function bindings. It is immutable and shared: building the
    // per-expression program is the only per-instance cost.
    private static final CelRuntime RUNTIME = CelRuntimeFactory.standardCelRuntimeBuilder().build();

    private final CelRuntime.Program program;
    // The source expression, kept so an evaluation failure can name the expression that failed.
    private final String expr;

    private RowExpressionProgram(CelRuntime.Program program, String expr) {
        this.program = program;
        this.expr = expr;
    }

    /** Compiles a predicate (bool) expression into an evaluable program. */
    static RowExpressionProgram predicate(String expr) {
        return of(RowExpressions.predicateAst(expr), expr);
    }

    /** Compiles a computed-value expression (any type) into an evaluable program. */
    static RowExpressionProgram value(String expr) {
        return of(RowExpressions.valueAst(expr), expr);
    }

    private static RowExpressionProgram of(CelAbstractSyntaxTree ast, String expr) {
        try {
            return new RowExpressionProgram(RUNTIME.createProgram(ast), expr);
        } catch (CelEvaluationException e) {
            // A checked AST builds into a program; a failure here is an invariant violation, not a
            // user condition.
            throw new IllegalStateException("row expression program could not be built", e);
        }
    }

    /** Evaluates the expression against one event, returning the raw CEL result. */
    Object eval(Envelope event) {
        Map<String, Object> vars = new HashMap<>(8);
        vars.put("op", event.op().symbol());
        vars.put("ts", event.ts());
        vars.put("src", event.src());
        vars.put("before", event.before() == null ? Map.of() : event.before());
        vars.put("after", event.after() == null ? Map.of() : event.after());
        vars.put("schema", event.schema() == null ? Map.of() : event.schema());
        try {
            return program.eval(vars);
        } catch (CelEvaluationException e) {
            // A row-level evaluation failure (a missing field, a type clash on a dyn value, a function
            // that type-checks but is unbound at runtime) is a user-diagnosable condition: surface it
            // as a coded diagnostic naming the expression, not a bare crash that fails the job opaquely.
            throw TransformErrors.expressionFailed(expr, e);
        }
    }
}
