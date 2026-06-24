package io.cyntex.core.schema;

/**
 * Deterministic JSON emitter with two-space indentation. Hand-written and dependency-free,
 * mirroring the canonical YAML emitter: the generated schema is a long-term artifact, so its
 * byte layout must be a stable contract the golden test can lock.
 */
final class JsonWriter {

    String write(Json node) {
        StringBuilder sb = new StringBuilder();
        emit(node, sb, 0);
        sb.append('\n');
        return sb.toString();
    }

    private void emit(Json node, StringBuilder sb, int indent) {
        switch (node) {
            case Json.Str s -> sb.append('"').append(escape(s.value())).append('"');
            case Json.Bool b -> sb.append(b.value() ? "true" : "false");
            case Json.Num n -> sb.append(n.raw());
            case Json.Arr a -> emitArray(a, sb, indent);
            case Json.Obj o -> emitObject(o, sb, indent);
        }
    }

    private void emitArray(Json.Arr arr, StringBuilder sb, int indent) {
        if (arr.items().isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        int child = indent + 1;
        for (int i = 0; i < arr.items().size(); i++) {
            indent(sb, child);
            emit(arr.items().get(i), sb, child);
            if (i < arr.items().size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent).append(']');
    }

    private void emitObject(Json.Obj obj, StringBuilder sb, int indent) {
        if (obj.entries().isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int child = indent + 1;
        for (int i = 0; i < obj.entries().size(); i++) {
            Json.Entry e = obj.entries().get(i);
            indent(sb, child).append('"').append(escape(e.key())).append("\": ");
            emit(e.value(), sb, child);
            if (i < obj.entries().size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent).append('}');
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
