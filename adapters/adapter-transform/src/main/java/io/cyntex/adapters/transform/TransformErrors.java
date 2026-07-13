package io.cyntex.adapters.transform;

import io.cyntex.core.common.CyntexException;

import java.util.Map;

/**
 * Builds the coded {@link CyntexException}s the transform ports raise from the {@code transform}
 * domain codes. One place, so every throw site supplies exactly the named arguments its code declares
 * and the evaluator diagnostic is extracted from the cause the same way.
 */
final class TransformErrors {

    private TransformErrors() {
    }

    /** A row expression that failed to evaluate; carries the expression text and the diagnostic. */
    static CyntexException expressionFailed(String expr, Throwable cause) {
        return new CyntexException(TransformError.EXPRESSION_FAILED,
                Map.of("expr", expr, "detail", detail(cause)), cause);
    }

    /** A js script that would not compile; carries the script-engine diagnostic. */
    static CyntexException scriptCompileFailed(Throwable cause) {
        return new CyntexException(TransformError.SCRIPT_COMPILE_FAILED, Map.of("detail", detail(cause)), cause);
    }

    /** A js script with no {@code process(record, ctx)} entry point. */
    static CyntexException scriptNoProcess() {
        return new CyntexException(TransformError.SCRIPT_NO_PROCESS, Map.of(), null);
    }

    /** A js script that threw while processing an event; carries the guest-side diagnostic. */
    static CyntexException scriptFailed(Throwable cause) {
        return new CyntexException(TransformError.SCRIPT_FAILED, Map.of("detail", detail(cause)), cause);
    }

    /** A js script whose output is not a valid record; {@code detail} names what was wrong. */
    static CyntexException scriptOutputInvalid(String detail) {
        return new CyntexException(TransformError.SCRIPT_OUTPUT_INVALID, Map.of("detail", detail), null);
    }

    // The developer-facing detail carried as the {detail} argument: the cause's own message, or its
    // type when it carries none, so the argument map always satisfies the code's placeholder contract.
    private static String detail(Throwable cause) {
        String message = cause.getMessage();
        return message != null ? message : cause.getClass().getSimpleName();
    }
}
