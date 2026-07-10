package io.cyntex.cli;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.messages.MessageCatalog;
import picocli.CommandLine.Help.Ansi;

import java.io.PrintWriter;
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

    /**
     * Renders a coded diagnostic to an error stream in the CLI's stable text shape — a bold-red
     * {@code error: <code>} header, then the catalog message, then the solution hint when the catalog
     * carries one. This is the one text renderer every face shares, so a coded diagnostic reads
     * identically whether an offline verb ({@code desc}, {@code connect}) or a connected online verb
     * raised it; the message and solution come from the same bundled catalog as the structured form.
     */
    static void printText(PrintWriter err, CyntexErrorCode code, Map<String, Object> args) {
        MessageCatalog.Rendered rendered = MessageCatalog.bundled().render(code, args);
        err.println(Ansi.AUTO.string("@|bold,red error:|@") + " " + code.code());
        err.println("  " + rendered.message());
        if (rendered.solution() != null) {
            err.println("  " + rendered.solution());
        }
        err.flush();
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
