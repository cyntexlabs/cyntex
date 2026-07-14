package io.cyntex.core.common;

import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON writer for the core ring: the counterpart to {@link JsonReader}. It
 * renders a {@code Map}/{@code List}/{@code String}/{@code Number}/{@code Boolean}/{@code null} tree back
 * to a compact JSON string — no indentation, no inter-token spaces — preserving object key order (the
 * {@code Map}'s own iteration order). The output parses back to an equal tree through {@link JsonReader},
 * so a value written here survives the round trip.
 *
 * <p>Lives in core-common alongside the reader because more than one module past the surface needs to
 * emit JSON without a library (rule R1 admission) — for example the HTTP face's stream frames, where a
 * third-party JSON library is not on the allowlist. It carries no business semantics.
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    /**
     * Renders a {@code Map}/{@code List}/scalar tree to a compact JSON string. Supported values are
     * {@code Map} (string keys), {@code List}, {@code String}, {@code Number}, {@code Boolean} and
     * {@code null}; any other value type is a programmer error and fails fast.
     */
    public static String write(Object root) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, root);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case Map<?, ?> map -> writeObject(sb, map);
            case List<?> list -> writeArray(sb, list);
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b.booleanValue() ? "true" : "false");
            case Number n -> sb.append(n.toString());
            default -> throw new IllegalArgumentException(
                    "cannot write value of type " + value.getClass().getName() + " as JSON");
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, element);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        // A control character with no named escape: four-digit lower-case hex.
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
