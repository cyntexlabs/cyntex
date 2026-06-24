package io.cyntex.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic YAML writer for an ordered tree of {@code Map<String,?>} / {@code List<?>} /
 * {@code String} / {@code Number} / {@code Boolean} / {@code null}. Block mappings and sequences,
 * two-space indentation, empty collections inline ({@code []} / <code>{}</code>), scalars
 * double-quoted only when a plain scalar would be unsafe, no trailing newline. The surface ring
 * carries no YAML library (rule R6), so this is hand-written.
 */
final class YamlOut {

    private YamlOut() {
    }

    static String write(Object root) {
        if (root instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return "{}";
            }
            return String.join("\n", mapLines(map, 0));
        }
        if (root instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            return String.join("\n", listLines(list, 0));
        }
        return scalar(root);
    }

    private static List<String> mapLines(Map<?, ?> map, int indent) {
        List<String> lines = new ArrayList<>();
        String pad = " ".repeat(indent);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child && !child.isEmpty()) {
                lines.add(pad + key + ":");
                lines.addAll(mapLines(child, indent + 2));
            } else if (value instanceof List<?> child && !child.isEmpty()) {
                lines.add(pad + key + ":");
                lines.addAll(listLines(child, indent + 2));
            } else {
                lines.add(pad + key + ": " + scalar(value));
            }
        }
        return lines;
    }

    private static List<String> listLines(List<?> list, int indent) {
        List<String> lines = new ArrayList<>();
        String pad = " ".repeat(indent);
        for (Object item : list) {
            if (item instanceof Map<?, ?> child && !child.isEmpty()) {
                List<String> inner = mapLines(child, indent + 2);
                // hoist the dash onto the item's first key; remaining keys keep the deeper indent
                inner.set(0, pad + "- " + inner.get(0).substring(indent + 2));
                lines.addAll(inner);
            } else if (item instanceof List<?> child && !child.isEmpty()) {
                lines.add(pad + "-");
                lines.addAll(listLines(child, indent + 2));
            } else {
                lines.add(pad + "- " + scalar(item));
            }
        }
        return lines;
    }

    private static String scalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return "{}";
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return "[]";
        }
        String s = String.valueOf(value);
        return isSafePlain(s) ? s : quote(s);
    }

    /** A plain scalar is safe only if it is a single unreserved word with no YAML metacharacters. */
    private static boolean isSafePlain(String s) {
        if (s.isEmpty() || !s.matches("[A-Za-z0-9_./@+-]+") || looksNumeric(s)) {
            return false;
        }
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "null", "true", "false", "yes", "no", "on", "off" -> false;
            default -> true;
        };
    }

    private static boolean looksNumeric(String s) {
        return s.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // other C0 controls are illegal raw in a double-quoted YAML scalar; escape them
                    // with the YAML backslash-u form, keeping the machine output well-formed
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
