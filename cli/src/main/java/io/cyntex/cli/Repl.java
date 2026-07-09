package io.cyntex.cli;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.schema.SchemaNavigator;
import io.cyntex.messages.MessageCatalog;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    static final List<String> BUILTINS =
            List.of("help", "exit", "quit", "cd", "pwd", "connect", "disconnect", "login", "logout");

    private final CommandLine commandLine;

    /** The transport seam to a server; a network-free fake is injected in tests. */
    private final ControlPlaneClient controlPlane;

    /** Reads the login password masked; a scripted fake is injected in tests, a JLine one bound in {@link #run}. */
    private Prompter prompter;

    /** The connection state, carried across read-loop iterations (offline until {@code connect}). */
    private final Session session = new Session();

    /** The session workspace: the current {@code cd} directory, injected into workspace-aware verbs. */
    private Path workdir;

    Repl(CommandLine commandLine) {
        this(commandLine, WorkspaceOption.resolve());
    }

    Repl(CommandLine commandLine, Path workdir) {
        this(commandLine, workdir, new HttpControlPlaneClient());
    }

    Repl(CommandLine commandLine, Path workdir, ControlPlaneClient controlPlane) {
        this(commandLine, workdir, controlPlane, null);
    }

    Repl(CommandLine commandLine, Path workdir, ControlPlaneClient controlPlane, Prompter prompter) {
        this.commandLine = commandLine;
        this.workdir = workdir;
        this.controlPlane = controlPlane;
        this.prompter = prompter;
    }

    /** The current session workspace. */
    Path workdir() {
        return workdir;
    }

    /** The current connection state. */
    Session session() {
        return session;
    }

    /**
     * The prompt: {@code cyntex(offline:<workspace>)> } while offline, {@code cyntex(<host:port>)> }
     * naming the landing node once connected, and {@code cyntex(<principal>@<host:port>)> } once
     * authenticated (the cluster name is not shown while membership is undiscovered in L1).
     */
    String prompt() {
        if (session.isAuthenticated()) {
            return "cyntex(" + session.principal() + "@" + hostPort(session.landingNode()) + ")> ";
        }
        if (session.isConnected()) {
            return "cyntex(" + hostPort(session.landingNode()) + ")> ";
        }
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
        if (words.get(0).equals("connect")) {
            connect(words);
            return true;
        }
        if (words.get(0).equals("disconnect")) {
            disconnect();
            return true;
        }
        if (words.get(0).equals("login")) {
            login(words);
            return true;
        }
        if (words.get(0).equals("logout")) {
            logout();
            return true;
        }
        commandLine.execute(withWorkspace(words));
        return true;
    }

    /**
     * Establishes a transport target from a comma-separated seed list, probing each seed in order and
     * landing on the first that answers {@code /healthz}. Connecting does not authenticate — the
     * session carries no credential. A blank argument, or a malformed / hostless seed token, is a
     * usage mistake (a benign message, not a coded error); a single invalid token rejects the whole
     * line before any probe, so a typo never silently connects to a subset. No reachable well-formed
     * seed is the coded {@code cli.connect-failed} diagnostic.
     */
    private void connect(List<String> words) {
        PrintWriter err = commandLine.getErr();
        String arg = words.size() > 1 ? words.get(1) : "";
        ParsedSeeds parsed = parseSeeds(arg);
        if (parsed.invalidToken() != null) {
            err.println("connect: invalid seed '" + parsed.invalidToken()
                    + "' (usage: connect <host:port>[,<host:port>...])");
            err.flush();
            return;
        }
        List<URI> seeds = parsed.valid();
        if (seeds.isEmpty()) {
            err.println("connect: missing operand (usage: connect <host:port>[,<host:port>...])");
            err.flush();
            return;
        }
        for (URI seed : seeds) {
            if (controlPlane.isHealthy(seed)) {
                session.connect(seeds, seed);
                PrintWriter out = commandLine.getOut();
                out.println("connected to " + hostPort(seed));
                out.flush();
                return;
            }
        }
        reportConnectFailed(seeds);
    }

    /** Clears the connection back to offline; a benign line either way, never an error. */
    private void disconnect() {
        PrintWriter out = commandLine.getOut();
        if (session.isConnected()) {
            session.disconnect();
            out.println("disconnected");
        } else {
            out.println("not connected");
        }
        out.flush();
    }

    /**
     * Authenticates the connected session as a human user: reads the password masked, verifies it via
     * {@code POST /auth/login}, and on success stores the returned bearer credential. Login requires an
     * established connection (authenticating is decoupled from connecting) and a username operand — both
     * absences are benign usage lines, not coded errors. A server refusal renders the server's coded
     * message (a bad credential is {@code control.auth-failed}, revealing nothing about which half was
     * wrong); an unreachable landing node is a benign transient line. The member set for failover is the
     * seeds until membership discovery lands.
     */
    private void login(List<String> words) {
        PrintWriter out = commandLine.getOut();
        PrintWriter err = commandLine.getErr();
        if (!session.isConnected()) {
            err.println("login: not connected (run connect first)");
            err.flush();
            return;
        }
        if (words.size() < 2 || words.get(1).isBlank()) {
            err.println("login: missing operand (usage: login <username>)");
            err.flush();
            return;
        }
        String username = words.get(1);
        String password = prompter.secret("Password");
        URI node = session.landingNode();
        switch (controlPlane.login(node, username, password)) {
            case LoginOutcome.Success success -> {
                session.authenticate(success.token(), username, null, session.seeds());
                out.println("logged in as " + username);
                out.flush();
            }
            case LoginOutcome.Rejected rejected -> {
                if (!rejected.code().isBlank()) {
                    err.println(Ansi.AUTO.string("@|bold,red error:|@") + " " + rejected.code());
                }
                err.println("  " + rejected.message());
                err.flush();
            }
            case LoginOutcome.Unreachable ignored -> {
                err.println("login: cannot reach " + hostPort(node));
                err.flush();
            }
        }
    }

    /**
     * Re-establishes the landing node after the current one is found unreachable: probes the member set
     * in order and re-lands on the first that answers, keeping the (cluster-wide) credential so the
     * session stays authenticated across the move. When no member answers the connection is lost and the
     * session returns to offline; an offline session is a no-op. This is the seam a connected verb
     * invokes on a request failure — L1's single-node member set exercises the same path (it is not
     * omitted for one node). Returns whether a landing node was kept.
     */
    boolean failover() {
        if (!session.isConnected()) {
            return false;
        }
        for (URI member : session.members()) {
            if (controlPlane.isHealthy(member)) {
                session.reland(member);
                PrintWriter out = commandLine.getOut();
                out.println("reconnected to " + hostPort(member));
                out.flush();
                return true;
            }
        }
        session.disconnect();
        PrintWriter err = commandLine.getErr();
        err.println("connection lost: no reachable cluster member");
        err.flush();
        return false;
    }

    /** Drops the credential while keeping the transport connection; a benign line either way. */
    private void logout() {
        PrintWriter out = commandLine.getOut();
        if (session.isAuthenticated()) {
            session.logout();
            out.println("logged out");
        } else {
            out.println("not logged in");
        }
        out.flush();
    }

    /** Renders the {@code cli.connect-failed} diagnostic through the message catalog to the err stream. */
    private void reportConnectFailed(List<URI> seeds) {
        String display = seeds.stream().map(URI::toString).collect(Collectors.joining(", "));
        CyntexException failure =
                new CyntexException(CliError.CONNECT_FAILED, Map.of("seeds", display), null);
        MessageCatalog.Rendered rendered =
                MessageCatalog.bundled().render(failure.code(), failure.args());
        PrintWriter err = commandLine.getErr();
        err.println(Ansi.AUTO.string("@|bold,red error:|@") + " " + failure.code().code());
        err.println("  " + rendered.message());
        if (rendered.solution() != null) {
            err.println("  " + rendered.solution());
        }
        err.flush();
    }

    /**
     * The outcome of parsing a seed argument: the host-bearing base URLs in order and, when a token
     * could not be turned into one, that first offending token ({@code invalidToken} is {@code null}
     * when every non-blank token parsed). A token is invalid when it is not a legal URI or resolves to
     * one with no host. Blank tokens are dropped, so an all-blank argument yields an empty list and a
     * {@code null} invalid token.
     */
    record ParsedSeeds(List<URI> valid, String invalidToken) {
    }

    /**
     * Parses a comma-separated seed argument into host-bearing base URLs without ever throwing. Each
     * element is trimmed and blanks dropped; one that already carries a scheme ({@code ://}) is kept
     * as-is, and a bare {@code host:port} gets an {@code http://} scheme. Parsing stops at the first
     * token that is not a legal URI or resolves to one with no host, returning it as the invalid token
     * so the caller can reject the whole line instead of crashing on it.
     */
    static ParsedSeeds parseSeeds(String arg) {
        List<URI> seeds = new ArrayList<>();
        for (String raw : arg.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(s.contains("://") ? s : "http://" + s);
            } catch (IllegalArgumentException e) {
                return new ParsedSeeds(List.of(), s);
            }
            if (uri.getHost() == null) {
                return new ParsedSeeds(List.of(), s);
            }
            seeds.add(uri);
        }
        return new ParsedSeeds(seeds, null);
    }

    /** The {@code host} or {@code host:port} of a base URL, for the prompt and success line. */
    private static String hostPort(URI uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        return port == -1 ? host : host + ":" + port;
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
            if (prompter == null) {
                // bind the masked-input reader to the REPL's own terminal (which this try owns and closes)
                prompter = new JLinePrompter(terminal, false);
            }
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
