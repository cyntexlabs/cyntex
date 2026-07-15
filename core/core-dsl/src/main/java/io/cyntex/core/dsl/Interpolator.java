package io.cyntex.core.dsl;

import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Configuration interpolation (grammar §9): {@code ${NAME}} reads a variable and
 * {@code ${var:NAME:default}} reads one with a fallback. Both are substituted once, when the document
 * is loaded, and only there — a per-record expression is a CEL expression, never a {@code ${}}.
 *
 * <p>This runs on raw text, before the parse, which is what fixes where the mechanism lives: the
 * loading side substitutes from its own environment and transmits values, so the variables read are
 * the author's own. A server-side substitution would read the server's environment instead, and hand
 * whoever wrote the document whatever the server process happens to hold.
 *
 * <p>Working on text rather than on a model is a consequence of that, not a shortcut: the loading
 * side does not parse. It costs the ability to confine substitution to config values, which is why
 * the refusals below are strict — an unresolvable reference must never survive as a literal.
 *
 * <p>A {@code $} that opens nothing is ordinary text and is left alone; passwords hold them.
 */
public final class Interpolator {

    /** The opening delimiter. A {@code $} not followed by {@code &#123;} is not a reference. */
    private static final String OPEN = "${";

    /** The prefix of the form that carries a default. */
    private static final String VAR_PREFIX = "var";

    /** What a variable may be named — the conventional environment-variable shape. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private Interpolator() {
    }

    /**
     * Replaces every reference in {@code text} with its value from {@code lookup}, which answers
     * {@code null} for a variable that is not set (the shape of {@link System#getenv(String)}).
     *
     * @throws DslException {@code dsl.undefined-variable} for a reference that resolves to nothing,
     *                      {@code dsl.malformed-interpolation} for one that is not a legal form
     */
    public static String interpolate(String text, UnaryOperator<String> lookup) {
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        while (true) {
            int open = text.indexOf(OPEN, cursor);
            if (open < 0) {
                return out.append(text, cursor, text.length()).toString();
            }
            out.append(text, cursor, open);
            int close = text.indexOf('}', open + OPEN.length());
            if (close < 0) {
                throw malformed(restOfLine(text, open), text, open);
            }
            out.append(resolve(text.substring(open + OPEN.length(), close), text, open, lookup));
            cursor = close + 1;
        }
    }

    /** Resolves one reference's body — what sits between the delimiters. */
    private static String resolve(String body, String text, int at, UnaryOperator<String> lookup) {
        int firstColon = body.indexOf(':');
        if (firstColon < 0) {
            return required(body, text, at, lookup);
        }
        if (!body.startsWith(VAR_PREFIX + ":")) {
            throw malformed(OPEN + body + "}", text, at);
        }
        // the default runs to the end of the body: colons inside it are content, not separators, or no
        // URI could ever be a default
        int secondColon = body.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            throw malformed(OPEN + body + "}", text, at);
        }
        String name = body.substring(firstColon + 1, secondColon);
        requireName(name, OPEN + body + "}", text, at);
        String value = lookup.apply(name);
        return value != null ? value : body.substring(secondColon + 1);
    }

    /** Reads a variable that has no default, and refuses to carry on without it. */
    private static String required(String name, String text, int at, UnaryOperator<String> lookup) {
        requireName(name, OPEN + name + "}", text, at);
        String value = lookup.apply(name);
        if (value == null) {
            throw new DslException(DslError.UNDEFINED_VARIABLE, null, line(text, at), column(text, at),
                    null, Map.of("name", name));
        }
        return value;
    }

    private static void requireName(String name, String ref, String text, int at) {
        if (!NAME.matcher(name).matches()) {
            throw malformed(ref, text, at);
        }
    }

    private static DslException malformed(String ref, String text, int at) {
        return new DslException(DslError.MALFORMED_INTERPOLATION, null, line(text, at), column(text, at),
                null, Map.of("ref", ref));
    }

    /**
     * The reference text to echo for an unclosed reference. Bounded at the line's end: without a
     * closing delimiter the reference has no end of its own, and echoing the rest of the document
     * would bury the location it is meant to point at.
     */
    private static String restOfLine(String text, int at) {
        int newline = text.indexOf('\n', at);
        return newline < 0 ? text.substring(at) : text.substring(at, newline);
    }

    private static int line(String text, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int column(String text, int at) {
        return at - (text.lastIndexOf('\n', at - 1) + 1) + 1;
    }
}
