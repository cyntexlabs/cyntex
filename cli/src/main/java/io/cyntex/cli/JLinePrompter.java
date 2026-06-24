package io.cyntex.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * The production {@link Prompter}: a JLine line reader over a terminal. Free-text questions show the
 * default as a hint, secrets read masked, and choices are printed as a numbered menu accepting either
 * the 1-based number or the option text. An empty reply (just Enter, or end-of-input) takes the last
 * option — the wizard's choice lists end with a skip / "(none)" sentinel, so that means "skip".
 *
 * <p>Built over the system terminal in normal use; a terminal can be injected to drive the adapter
 * over fixed streams in tests.
 */
final class JLinePrompter implements Prompter, AutoCloseable {

    private final Terminal terminal;
    private final LineReader reader;
    private final boolean ownsTerminal;

    JLinePrompter(Terminal terminal, boolean ownsTerminal) {
        this.terminal = terminal;
        this.reader = LineReaderBuilder.builder().terminal(terminal).build();
        this.ownsTerminal = ownsTerminal;
    }

    /** Opens a prompter over the system terminal; the caller closes it. */
    static JLinePrompter system() throws IOException {
        return new JLinePrompter(TerminalBuilder.builder().system(true).build(), true);
    }

    @Override
    public String ask(String question, String defaultValue) {
        String prompt = defaultValue == null || defaultValue.isEmpty()
                ? question + ": "
                : question + " [" + defaultValue + "]: ";
        return readLine(prompt, null);
    }

    @Override
    public String secret(String question) {
        return readLine(question + ": ", '*');
    }

    @Override
    public String choose(String question, List<String> options) {
        PrintWriter out = terminal.writer();
        out.println(question + ":");
        for (int i = 0; i < options.size(); i++) {
            out.println("  " + (i + 1) + ") " + options.get(i));
        }
        out.flush();
        while (true) {
            String line = readLine("  choice [1-" + options.size() + "]: ", null);
            if (line.isEmpty()) {
                return options.get(options.size() - 1); // Enter / end-of-input = the skip sentinel
            }
            try {
                int index = Integer.parseInt(line);
                if (index >= 1 && index <= options.size()) {
                    return options.get(index - 1);
                }
            } catch (NumberFormatException notANumber) {
                if (options.contains(line)) {
                    return line;
                }
            }
            out.println("  please enter 1-" + options.size() + " or an option name");
            out.flush();
        }
    }

    @Override
    public String lines(String question) {
        PrintWriter out = terminal.writer();
        out.println(question + " (end with a single '.' on its own line):");
        out.flush();
        return captureBlock(this::readBlockLine);
    }

    /** One raw block line, or {@code null} at end of input; not trimmed — SQL / JS indentation matters. */
    private String readBlockLine() {
        try {
            return reader.readLine("  | ");
        } catch (EndOfFileException | UserInterruptException end) {
            return null;
        }
    }

    /**
     * Accumulate lines from {@code source} until a lone {@code .} or end of input ({@code null}),
     * preserving each line verbatim. Returns the joined lines with no trailing newline; the caller
     * normalizes block-scalar layout. An immediately-ended block returns the empty string.
     */
    static String captureBlock(java.util.function.Supplier<String> source) {
        StringBuilder block = new StringBuilder();
        while (true) {
            String line = source.get();
            if (line == null || line.strip().equals(".")) {
                break;
            }
            block.append(line).append('\n');
        }
        return block.isEmpty() ? "" : block.substring(0, block.length() - 1);
    }

    private String readLine(String prompt, Character mask) {
        try {
            String line = mask == null ? reader.readLine(prompt) : reader.readLine(prompt, mask);
            return line == null ? "" : line.trim();
        } catch (EndOfFileException | UserInterruptException end) {
            return ""; // no more input — the caller treats empty as skip / default
        }
    }

    @Override
    public void close() {
        if (ownsTerminal) {
            try {
                terminal.close();
            } catch (IOException ignore) {
                // best effort on shutdown
            }
        }
    }
}
