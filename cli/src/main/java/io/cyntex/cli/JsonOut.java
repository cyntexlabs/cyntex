package io.cyntex.cli;

import java.util.List;
import java.util.Map;

/**
 * Deterministic JSON writer for an ordered tree of {@code Map<String,?>} / {@code List<?>} /
 * {@code String} / {@code Number} / {@code Boolean} / {@code null}. Two-space indented, one element
 * per line, empty collections inline, no trailing newline (the caller decides line endings). The
 * surface ring carries no JSON library (rule R6), so this is hand-written.
 */
final class JsonOut {

    private JsonOut() {
    }

    static String write(Object root) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, root, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, indent);
        } else {
            sb.append(scalar(value));
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(sb, indent + 2);
            sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\": ");
            writeValue(sb, entry.getValue(), indent + 2);
            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, indent + 2);
            writeValue(sb, list.get(i), indent + 2);
            if (i + 1 < list.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static String scalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        return '"' + escape(String.valueOf(value)) + '"';
    }

    private static void indent(StringBuilder sb, int spaces) {
        sb.append(" ".repeat(spaces));
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
