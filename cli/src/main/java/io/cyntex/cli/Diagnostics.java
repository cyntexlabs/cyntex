package io.cyntex.cli;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.messages.MessageCatalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders a coded diagnostic into the CLI's stable structured shape — the single source of truth for
 * the {@code {code, severity, message, solution?, source?, line?, column?, params?}} envelope the
 * offline verbs emit ({@code validate}, {@code desc}, {@code new}). The message and solution come from
 * the bundled message catalog; {@code params} carries the named arguments sorted for a stable machine
 * contract regardless of throw-site order, and the location fields appear only when known.
 */
final class Diagnostics {

    private Diagnostics() {
    }

    /** One coded diagnostic as a stable, machine-readable map. */
    static Map<String, Object> map(CyntexErrorCode code, Map<String, Object> args, String source, int line, int column) {
        MessageCatalog.Rendered rendered = MessageCatalog.bundled().render(code, args);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", code.code());
        d.put("severity", code.severity().name());
        d.put("message", rendered.message());
        if (rendered.solution() != null) {
            d.put("solution", rendered.solution());
        }
        if (source != null) {
            d.put("source", source);
        }
        if (line > 0) {
            d.put("line", line);
        }
        if (column > 0) {
            d.put("column", column);
        }
        if (args != null && !args.isEmpty()) {
            d.put("params", new TreeMap<>(args));   // sorted for a stable machine contract
        }
        return d;
    }
}
