package io.cyntex.e2e;

import java.util.List;
import java.util.Map;

/**
 * Renders a {@code Map}/{@code List}/scalar tree as indented JSON.
 *
 * <p>The core ring's writer is deliberately compact - it feeds wire frames, where indentation is
 * waste. The artifacts here are the opposite kind of output: they are checked in, and the way they
 * are kept honest is that a regeneration is read as a diff. A one-line document has no reviewable
 * diff, so this writes one key per line.
 *
 * <p>It formats and decides nothing, which is why a second formatter existing is not the kind of
 * duplication that drifts: the vocabulary it prints has exactly one source, and that source is
 * {@link Vocabulary}.
 */
final class SpecJson {

    private static final String INDENT = "  ";

    private SpecJson() {
    }

    /** The tree as indented JSON, one trailing newline, so the file ends the way a text file should. */
    static String write(Object root) {
        StringBuilder out = new StringBuilder();
        writeValue(out, root, 0);
        return out.append('\n').toString();
    }

    private static void writeValue(StringBuilder out, Object value, int depth) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> writeObject(out, map, depth);
            case List<?> list -> writeArray(out, list, depth);
            case String text -> writeString(out, text);
            case Boolean flag -> out.append(flag.booleanValue());
            case Number number -> out.append(number);
            default -> throw new IllegalArgumentException(
                    "not a JSON value: " + value.getClass() + " — the tree is built here, so this is a bug");
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int remaining = map.size();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(out, depth + 1);
            writeString(out, String.valueOf(entry.getKey()));
            out.append(": ");
            writeValue(out, entry.getValue(), depth + 1);
            out.append(--remaining > 0 ? ",\n" : "\n");
        }
        indent(out, depth);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(out, depth + 1);
            writeValue(out, list.get(i), depth + 1);
            out.append(i < list.size() - 1 ? ",\n" : "\n");
        }
        indent(out, depth);
        out.append(']');
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append(INDENT.repeat(depth));
    }
}
