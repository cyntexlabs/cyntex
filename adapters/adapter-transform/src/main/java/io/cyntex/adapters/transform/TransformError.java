package io.cyntex.adapters.transform;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/**
 * The {@code transform} domain's error codes: the first-party, diagnosable failures a stateless row
 * transform raises while it evaluates an author's expression or script. These are user-facing — the
 * author wrote a CEL expression that fails against the row it meets, or a js script that will not
 * compile, defines no entry point, throws at runtime, or produces output that is not a record.
 *
 * <p>Two-segment {@code transform.<symbol>} codes; they go through the full build-time gate set like
 * any other first-party domain. Programmer errors / invariant violations stay bare and are allowed to
 * crash — a checked AST that will not build into a program, for instance, is not laundered into a
 * {@code transform.*} code that would hide the defect. {@code placeholders()} is the named-argument
 * contract: every throw site supplies a value for each name, and the build-time placeholder gate
 * checks the catalog templates against it.
 */
public enum TransformError implements CyntexErrorCode {

    /**
     * A row expression (a filter predicate or a map computed value) failed to evaluate against an
     * event. {@code expr} is the expression text; {@code detail} is the evaluator diagnostic — a
     * missing field, a type clash on a dyn value, or a function that type-checks but is unbound at
     * runtime.
     */
    EXPRESSION_FAILED("transform.expression-failed", Set.of("expr", "detail")),

    /** A js transform script could not be compiled. {@code detail} is the script-engine diagnostic. */
    SCRIPT_COMPILE_FAILED("transform.script-compile-failed", Set.of("detail")),

    /** A js transform script defines no {@code process(record, ctx)} function — its required entry point. */
    SCRIPT_NO_PROCESS("transform.script-no-process", Set.of()),

    /**
     * A js transform script raised an error while processing an event. {@code detail} is the
     * guest-side failure the script engine reported.
     */
    SCRIPT_FAILED("transform.script-failed", Set.of("detail")),

    /**
     * A js transform script produced output that is not a valid record — a non-record return value, or
     * a {@code before} / {@code after} / {@code schema} that is not an object. {@code detail} names
     * what was wrong.
     */
    SCRIPT_OUTPUT_INVALID("transform.script-output-invalid", Set.of("detail"));

    private final String code;
    private final Set<String> placeholders;

    TransformError(String code, Set<String> placeholders) {
        this.code = code;
        this.placeholders = placeholders;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> placeholders() {
        return placeholders;
    }
}
