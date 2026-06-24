package io.cyntex.tools.catalog.assembler;

import java.util.List;
import java.util.Map;

/**
 * Deterministic JSON emitter over a {@code Map}/{@code List}/{@code String}/{@code Boolean}/
 * {@code Number}/{@code null} tree — the shape the catalog entry writer builds and the dual of
 * core-catalog's {@code CatalogJson} reader. Hand-written and dependency-free: fully expanded (one
 * element per line), two-space indent, empty collections inline, with a trailing newline, so the
 * generated catalog has a stable byte layout the golden test can lock. Object key order is the
 * caller's map iteration order, so callers pass insertion-ordered maps for determinism.
 */
final class JsonWriter {

    String write(Object tree) {
        StringBuilder sb = new StringBuilder();
        emit(tree, sb, 0);
        sb.append('\n');
        return sb.toString();
    }

    private void emit(Object node, StringBuilder sb, int indent) {
        if (node == null) {
            sb.append("null");
        } else if (node instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (node instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (node instanceof Number n) {
            sb.append(n);
        } else if (node instanceof Map<?, ?> m) {
            emitObject(m, sb, indent);
        } else if (node instanceof List<?> l) {
            emitArray(l, sb, indent);
        } else {
            throw new IllegalArgumentException("unsupported JSON node type: " + node.getClass());
        }
    }

    private void emitObject(Map<?, ?> obj, StringBuilder sb, int indent) {
        if (obj.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int child = indent + 1;
        int i = 0;
        for (Map.Entry<?, ?> entry : obj.entrySet()) {
            indent(sb, child).append('"').append(escape(String.valueOf(entry.getKey()))).append("\": ");
            emit(entry.getValue(), sb, child);
            if (++i < obj.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent).append('}');
    }

    private void emitArray(List<?> arr, StringBuilder sb, int indent) {
        if (arr.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        int child = indent + 1;
        for (int i = 0; i < arr.size(); i++) {
            indent(sb, child);
            emit(arr.get(i), sb, child);
            if (i < arr.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent).append(']');
    }

    private static StringBuilder indent(StringBuilder sb, int level) {
        return sb.append("  ".repeat(level));
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
