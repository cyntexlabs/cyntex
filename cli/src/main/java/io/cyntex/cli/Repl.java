package io.cyntex.cli;

import io.cyntex.core.schema.SchemaNavigator;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The offline REPL: a JLine read loop over the same command table the one-shot mode uses, so a verb
 * behaves identically whether typed at the prompt or passed as arguments. Builtins are {@code help}
 * (usage), {@code exit} / {@code quit}, and the workspace builtins {@code cd} / {@code pwd}; everything
 * else is dispatched as a verb. {@link #dispatch} is the testable seam; {@link #run} wraps it with the
 * JLine terminal.
 *
 * <p>The REPL carries a session workspace (the current {@code cd} directory). A dispatched verb that
 * declares {@code --workdir} but does not set it on the line inherits this session workspace — so a bare
 * {@code validate} targets where the session sits, not the process-relative {@code cyn-work} default.
 */
final class Repl {

    /** REPL-only words handled here rather than by the command table; completed alongside the verbs. */
    static final List<String> BUILTINS = List.of("help", "exit", "quit", "cd", "pwd");

    private final CommandLine commandLine;

    /** The session workspace: the current {@code cd} directory, injected into workspace-aware verbs. */
    private Path workdir;

    Repl(CommandLine commandLine) {
        this(commandLine, WorkspaceOption.resolve());
    }

    Repl(CommandLine commandLine, Path workdir) {
        this.commandLine = commandLine;
        this.workdir = workdir;
    }

    /** The current session workspace. */
    Path workdir() {
        return workdir;
    }

    /** The prompt, naming the current workspace, e.g. {@code cyntex(offline:cyn-work)> }. */
    String prompt() {
        Path name = workdir.getFileName();
        String label = name != null ? name.toString() : workdir.toString();
        return "cyntex(offline:" + label + ")> ";
    }

    /** Handles one input line. Returns {@code false} when the loop should stop (exit / quit). */
    boolean dispatch(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (trimmed.equals("exit") || trimmed.equals("quit")) {
            return false;
        }
        if (trimmed.equals("help")) {
            commandLine.usage(commandLine.getOut());
            commandLine.getOut().flush();
            return true;
        }
        if (trimmed.equals("pwd")) {
            commandLine.getOut().println(workdir.toString());
            commandLine.getOut().flush();
            return true;
        }
        List<String> words = tokenize(trimmed);
        if (words.isEmpty()) {
            return true;
        }
        if (words.get(0).equals("cd")) {
            changeDir(words);
            return true;
        }
        commandLine.execute(withWorkspace(words));
        return true;
    }

    /** Changes the session workspace to an existing directory, resolved against the current one. */
    private void changeDir(List<String> words) {
        PrintWriter err = commandLine.getErr();
        if (words.size() < 2) {
            err.println("cd: missing operand");
            err.flush();
            return;
        }
        String arg = words.get(1);
        Path target = workdir.resolve(arg).normalize();
        if (!Files.isDirectory(target)) {
            err.println("cd: not a directory: " + arg);
            err.flush();
            return;
        }
        workdir = target;
    }

    /**
     * Appends the session {@code --workdir} to a verb that declares it but did not set it on the line,
     * so the session workspace governs. Verbs without the option (e.g. {@code explain}) are left alone —
     * injecting there would be an unknown option. An explicit {@code -w} on the line is left to win.
     */
    private String[] withWorkspace(List<String> words) {
        CommandLine sub = commandLine.getSubcommands().get(words.get(0));
        boolean acceptsWorkdir = sub != null && sub.getCommandSpec().findOption("--workdir") != null;
        boolean alreadySet = words.stream().anyMatch(w ->
                w.equals("-w") || w.equals("--workdir") || w.startsWith("-w=") || w.startsWith("--workdir="));
        if (acceptsWorkdir && !alreadySet) {
            List<String> augmented = new ArrayList<>(words);
            augmented.add("--workdir");
            augmented.add(workdir.toString());
            return augmented.toArray(new String[0]);
        }
        return words.toArray(new String[0]);
    }

    /**
     * Splits a REPL line into argument words, honoring single / double quotes so a path with spaces
     * survives as one argument — the one-shot form gets this de-quoting from the OS shell, so the
     * REPL must do it itself to keep the two forms identical. Matched quotes are stripped; an
     * unmatched quote runs to end of line.
     */
    static List<String> tokenize(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inWord = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(current.toString());
                    current.setLength(0);
                    inWord = false;
                }
            } else {
                current.append(c);
                inWord = true;
            }
        }
        if (inWord) {
            words.add(current.toString());
        }
        return words;
    }

    /** Runs the interactive read loop until {@code exit} / {@code quit} or end-of-input. */
    void run() {
        PrintWriter out = commandLine.getOut();
        out.println("Cyntex offline CLI. Type 'help' for commands, 'exit' to quit.");
        out.flush();
        // system(true) for a real terminal; dumb(true) degrades silently to a dumb terminal when
        // there is no TTY (piped / redirected input) instead of printing a JLine warning.
        try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(CyntexCompleter.forRepl(commandLine, SchemaNavigator.bundled()))
                    .build();
            while (true) {
                String line;
                try {
                    line = reader.readLine(prompt());
                } catch (UserInterruptException e) {
                    continue;   // Ctrl-C clears the current line and keeps the session
                } catch (EndOfFileException e) {
                    break;      // Ctrl-D ends the session
                }
                if (!dispatch(line)) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.println("bye");
        out.flush();
    }
}
