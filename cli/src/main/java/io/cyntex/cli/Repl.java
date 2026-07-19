package io.cyntex.cli;

import io.cyntex.core.dsl.DslException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.dsl.Interpolator;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceResource;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
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

    /**
     * Registry verbs a connected session routes to the server instead of the offline command table. The
     * artifact verbs ({@code apply} = {@code artifact.apply}, {@code get} = {@code artifact.get},
     * {@code ls} = {@code artifact.list}), the four pipeline lifecycle verbs ({@code start} / {@code stop}
     * / {@code pause} / {@code resume} = {@code pipeline.*}), the three observation read faces
     * ({@code status} / {@code metrics} / {@code snapshot} = {@code pipeline.status} / {@code pipeline.metrics}
     * / {@code pipeline.snapshot}) and the log tail ({@code logs} = {@code pipeline.logs}), the connection
     * verbs ({@code test} = {@code connection.test}, {@code test-result} = {@code connection.test-result},
     * {@code discover-schema} = {@code connection.discover-schema}, {@code schema} = {@code connection.schema}),
     * and the connector verbs ({@code register} = {@code connector.register}, {@code connectors} =
     * {@code connector.list}). Offline they fall through to the table, where the connected verbs report a
     * coded {@code cli.not-connected} and {@code ls} browses the local workspace. {@code validate} is not here — it
     * runs the full local stack in either state until a server validate endpoint exists.
     */
    private static final List<String> ONLINE_VERBS = List.of(
            "apply", "get", "ls", "start", "stop", "pause", "resume", "status", "metrics", "snapshot",
            "logs", "test", "test-result", "discover-schema", "schema", "register", "connectors");

    private final CommandLine commandLine;

    /** The transport seam to a server; a network-free fake is injected in tests. */
    private final ControlPlaneClient controlPlane;

    /** Reads the login password masked; a scripted fake is injected in tests, a JLine one bound in {@link #run}. */
    private Prompter prompter;

    /** The connection state, carried across read-loop iterations (offline until {@code connect}). */
    private final Session session = new Session();

    /** The session workspace: the current {@code cd} directory, injected into workspace-aware verbs. */
    private Path workdir;

    /**
     * The environment {@code ${...}} references are substituted from — the author's own, since this side
     * loads the files. A scripted stand-in is injected in tests; the real one is the process environment.
     */
    private final UnaryOperator<String> env;

    /**
     * Set by the terminal's interrupt handler to stop an in-flight {@code --watch} / {@code --follow}
     * stream; reset at the start of each stream. Volatile because the interrupt handler runs on another
     * thread than the stream loop that polls it.
     */
    private volatile boolean streamCancelled;

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
        this(commandLine, workdir, controlPlane, prompter, System::getenv);
    }

    Repl(CommandLine commandLine, Path workdir, ControlPlaneClient controlPlane, Prompter prompter,
         UnaryOperator<String> env) {
        this.commandLine = commandLine;
        this.workdir = workdir;
        this.controlPlane = controlPlane;
        this.prompter = prompter;
        this.env = env;
    }

    /** The current session workspace. */
    Path workdir() {
        return workdir;
    }

    /** The current connection state. */
    Session session() {
        return session;
    }

    /** Requests any in-flight {@code --watch} / {@code --follow} stream to stop; wired to Ctrl-C in {@link #run}. */
    void cancelStream() {
        streamCancelled = true;
    }

    private boolean isStreamCancelled() {
        return streamCancelled;
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
        if (session.isConnected() && ONLINE_VERBS.contains(words.get(0))) {
            onlineVerb(words);
            return true;
        }
        commandLine.execute(withWorkspace(words));
        return true;
    }

    /**
     * Routes a connected-session verb (apply / get / ls) to the server. Every {@code /api} verb requires a
     * credential, so an unauthenticated session short-circuits with a benign "run login" line rather than
     * provoking a server 401. On a request the landing node cannot answer, {@link #failover} re-lands on
     * another member and the verb is retried once against the new node.
     */
    private void onlineVerb(List<String> words) {
        PrintWriter err = commandLine.getErr();
        if (!session.isAuthenticated()) {
            Diagnostics.printText(err, CliError.NOT_AUTHENTICATED, Map.of("verb", words.get(0)));
            return;
        }
        // `test` and its read-back `test-result` return a structured report that is worth machine-reading, so
        // they accept an `-o` output flag and parse their own options — routed before the positional-only
        // guard the other verbs share.
        if (words.get(0).equals("test")) {
            testOnline(words);
            return;
        }
        if (words.get(0).equals("test-result")) {
            testResultOnline(words);
            return;
        }
        if (words.get(0).equals("discover-schema")) {
            discoverSchemaOnline(words);
            return;
        }
        if (words.get(0).equals("schema")) {
            schemaOnline(words);
            return;
        }
        // `register` uploads a local artifact and returns a structured report worth machine-reading, so it
        // accepts an `-o` output flag and parses its own operand (a local path) — routed before the
        // positional-only guard the other verbs share.
        if (words.get(0).equals("register")) {
            registerOnline(words);
            return;
        }
        // `connectors` lists the online catalog and returns a structured list worth machine-reading, so it
        // accepts an `-o` output flag and takes no operand — routed before the positional-only guard.
        if (words.get(0).equals("connectors")) {
            connectorsOnline(words);
            return;
        }
        // The two streaming sugars ride the read verbs over the websocket channel: `status --watch` and
        // `logs --follow`. They are the only dash-options a connected verb accepts, and only on their verb.
        if (words.get(0).equals("status") && words.contains("--watch")) {
            statusWatch(words);
            return;
        }
        if (words.get(0).equals("logs") && words.contains("--follow")) {
            logsFollow(words);
            return;
        }
        // The other connected verbs take positional operands only; a dash-option (e.g. `-o json`) is not yet
        // supported and must not be silently misread as an id / kind / path.
        for (int i = 1; i < words.size(); i++) {
            if (words.get(i).startsWith("-")) {
                err.println(words.get(0) + ": options are not supported on a connected verb yet");
                err.flush();
                return;
            }
        }
        switch (words.get(0)) {
            case "apply" -> applyOnline(words);
            case "get" -> getOnline(words);
            case "ls" -> lsOnline(words);
            case "start", "stop", "pause", "resume" -> lifecycleOnline(words);
            case "status" -> statusOnline(words);
            case "metrics" -> metricsOnline(words);
            case "snapshot" -> snapshotOnline(words);
            case "logs" -> logsOnline(words);
            default -> throw new IllegalStateException("not an online verb: " + words.get(0));
        }
    }

    /**
     * {@code <verb> <pipeline-id>} — issues a pipeline lifecycle verb (start / stop / pause / resume) on the
     * server and prints the pipeline's new desired state ({@code <id>  <state>}). A missing id is a benign
     * usage line; a coded refusal — an unknown pipeline, a transition the state machine forbids, or a
     * start/resume at a stale revision — renders its code and message. On a request the landing node cannot
     * answer, {@link #withFailover} re-lands and retries once. There is no {@code rewind} verb: a re-dig is
     * the explicit two-step {@code stop} then {@code start}.
     */
    private void lifecycleOnline(List<String> words) {
        String verb = words.get(0);
        PrintWriter err = commandLine.getErr();
        if (words.size() < 2 || words.get(1).isBlank()) {
            err.println(verb + ": missing operand (usage: " + verb + " <pipeline-id>)");
            err.flush();
            return;
        }
        String id = words.get(1);
        LifecycleOutcome outcome = withFailover(() ->
                controlPlane.lifecycle(session.landingNode(), session.credential(), id, verb),
                o -> o instanceof LifecycleOutcome.Unreachable);
        PrintWriter out = commandLine.getOut();
        switch (outcome) {
            case LifecycleOutcome.Accepted accepted -> {
                out.println(accepted.pipelineId() + "  " + accepted.targetState().toLowerCase(Locale.ROOT));
                out.flush();
            }
            case LifecycleOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case LifecycleOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * {@code apply [path]} — reads every {@code *.cyn.yml} under the path (default: the session workspace)
     * as raw drafts and applies them as one batch via the server, which re-parses and re-validates. Prints
     * one line per artifact naming how it changed (created / updated / unchanged). An empty or unreadable
     * path is a benign usage line; a coded server refusal (a validation failure is a {@code dsl.*} code)
     * renders its code and message.
     */
    private void applyOnline(List<String> words) {
        PrintWriter err = commandLine.getErr();
        Path target = words.size() > 1 ? workdir.resolve(words.get(1)).normalize() : workdir;
        List<LocalDraft> drafts;
        try {
            drafts = collectDrafts(target);
        } catch (IOException e) {
            err.println("apply: cannot read " + target + ": " + e.getMessage());
            err.flush();
            return;
        } catch (DslException e) {
            // an unresolved reference is refused here, before anything is sent: the server would only see
            // a literal ${...} and take it for a real value
            renderLocalRefusal(e);
            return;
        }
        if (drafts.isEmpty()) {
            err.println("apply: no *.cyn.yml artifacts found in " + target);
            err.flush();
            return;
        }
        ApplyOutcome outcome = withFailover(() ->
                controlPlane.apply(session.landingNode(), session.credential(), drafts),
                o -> o instanceof ApplyOutcome.Unreachable);
        PrintWriter out = commandLine.getOut();
        switch (outcome) {
            case ApplyOutcome.Applied applied -> {
                for (ApplyOutcome.Item item : applied.items()) {
                    out.println(item.change().toLowerCase(Locale.ROOT) + "  " + item.kind() + "  " + item.id());
                }
                out.flush();
            }
            case ApplyOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case ApplyOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * {@code get <id>} — reads one artifact from the server (server-as-truth) and prints its canonical
     * form. A missing operand is a benign usage line; an id that resolves to nothing is a benign
     * "not found" line; a coded refusal renders its code and message.
     */
    private void getOnline(List<String> words) {
        PrintWriter err = commandLine.getErr();
        if (words.size() < 2 || words.get(1).isBlank()) {
            err.println("get: missing operand (usage: get <id>)");
            err.flush();
            return;
        }
        String id = words.get(1);
        GetOutcome outcome = withFailover(() ->
                controlPlane.get(session.landingNode(), session.credential(), id),
                o -> o instanceof GetOutcome.Unreachable);
        PrintWriter out = commandLine.getOut();
        switch (outcome) {
            case GetOutcome.Found found -> {
                out.println(found.artifact().canonicalForm().stripTrailing());
                out.flush();
            }
            case GetOutcome.Absent ignored -> {
                err.println("not found: " + id);
                err.flush();
            }
            case GetOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case GetOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * {@code ls [kind]} — lists the artifacts the server holds, optionally filtered by kind (the connected
     * counterpart of the offline workspace browser). Prints {@code kind  id} per artifact, or a benign
     * "no resources" line when the store is empty; a coded refusal renders its code and message.
     */
    private void lsOnline(List<String> words) {
        String kind = words.size() > 1 ? words.get(1) : null;
        ListOutcome outcome = withFailover(() ->
                controlPlane.list(session.landingNode(), session.credential(), kind),
                o -> o instanceof ListOutcome.Unreachable);
        PrintWriter out = commandLine.getOut();
        switch (outcome) {
            case ListOutcome.Listed listed -> {
                if (listed.artifacts().isEmpty()) {
                    out.println("no resources");
                } else {
                    for (RemoteArtifact artifact : listed.artifacts()) {
                        out.println(artifact.kind() + "  " + artifact.id());
                    }
                }
                out.flush();
            }
            case ListOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case ListOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * {@code test <id> [-o text|json|yaml]} — tests a stored connection. It reads the connection from the
     * server first (server-as-truth), parses the connector and connection config it holds, then posts the
     * connection test and renders the report the connector returned. A missing operand or an unknown option
     * is a benign usage line; an id that resolves to nothing is a benign "not found"; an id that is not a
     * source connection is a benign "not a testable connection"; a coded refusal renders its code and
     * message. A failed connection is still a rendered report (the test ran), not an error.
     */
    private void testOnline(List<String> words) {
        IdAndFormat parsed = parseIdAndFormat("test", words);
        if (parsed == null) {
            return;
        }
        SourceResource source = fetchSourceConnection("test", parsed.id(), "testable");
        if (source == null) {
            return;
        }

        final String connectionId = parsed.id();
        OutputFormat chosen = parsed.format();
        ConnectionTestOutcome outcome = withFailover(() -> controlPlane.test(
                session.landingNode(), session.credential(), connectionId, source.connector(), source.config()),
                o -> o instanceof ConnectionTestOutcome.Unreachable);
        switch (outcome) {
            case ConnectionTestOutcome.Tested tested -> renderReport(tested.report(), chosen);
            case ConnectionTestOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case ConnectionTestOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * Reads the stored connection a probing verb targets (server-as-truth, so the probe runs against
     * exactly what is stored) and parses it to a source connection, or reports why it cannot be probed
     * and returns {@code null}: a benign "not found" for a missing id, a benign "not a {adjective}
     * connection" for a non-source kind (using the reliable stored kind, without parsing a body that is
     * not a connection at all), and a benign "cannot read" for a stored body that no longer parses to a
     * source — a diagnosable state, not a crash.
     */
    private SourceResource fetchSourceConnection(String verb, String connectionId, String adjective) {
        PrintWriter err = commandLine.getErr();
        GetOutcome got = withFailover(() ->
                controlPlane.get(session.landingNode(), session.credential(), connectionId),
                o -> o instanceof GetOutcome.Unreachable);
        if (!(got instanceof GetOutcome.Found found)) {
            switch (got) {
                case GetOutcome.Absent ignored -> {
                    err.println("not found: " + connectionId);
                    err.flush();
                }
                case GetOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
                case GetOutcome.Unreachable ignored -> reportRequestFailed();
                case GetOutcome.Found ignored -> { }   // handled by the outer guard; unreachable here
            }
            return null;
        }
        if (!found.artifact().kind().equals("source")) {
            err.println(verb + ": '" + connectionId + "' is a " + found.artifact().kind()
                    + ", not a " + adjective + " connection");
            err.flush();
            return null;
        }
        Resource resource;
        try {
            resource = new DslParser().parse(found.artifact().canonicalForm());
        } catch (RuntimeException malformed) {
            err.println(verb + ": cannot read connection '" + connectionId + "'");
            err.flush();
            return null;
        }
        if (!(resource instanceof SourceResource source)) {
            // the stored kind claimed source but the body did not parse to one — treat as unreadable
            err.println(verb + ": cannot read connection '" + connectionId + "'");
            err.flush();
            return null;
        }
        return source;
    }

    /**
     * {@code discover-schema <id> [-o text|json|yaml]} — discovers a stored connection's source model. It
     * reads the connection from the server first (server-as-truth), parses the connector and connection
     * config it holds, then posts the discovery and renders the discovered tables. A missing operand or an
     * unknown option is a benign usage line; an id that resolves to nothing is a benign "not found"; an id
     * that is not a source connection is a benign "not a discoverable connection"; a coded refusal renders
     * its code and message.
     */
    private void discoverSchemaOnline(List<String> words) {
        IdAndFormat parsed = parseIdAndFormat("discover-schema", words);
        if (parsed == null) {
            return;
        }
        SourceResource source = fetchSourceConnection("discover-schema", parsed.id(), "discoverable");
        if (source == null) {
            return;
        }

        final String connectionId = parsed.id();
        ConnectionDiscoverSchemaOutcome outcome = withFailover(() -> controlPlane.discoverSchema(
                session.landingNode(), session.credential(), connectionId, source.connector(), source.config()),
                o -> o instanceof ConnectionDiscoverSchemaOutcome.Unreachable);
        switch (outcome) {
            case ConnectionDiscoverSchemaOutcome.Discovered discovered ->
                    renderSchema(discovered.schema(), parsed.format());
            case ConnectionDiscoverSchemaOutcome.Rejected rejected ->
                    renderRejection(rejected.code(), rejected.message());
            case ConnectionDiscoverSchemaOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * {@code schema <id> [table] [-o text|json|yaml]} — reads a connection's latest discovered source model
     * and renders it (the read peer of {@code discover-schema}, no discovery run). With a table operand the
     * view narrows to that table — a presentation-side projection of the full stored model, not a separate
     * server call; a table not in the model is a benign line naming the miss. A connection that has never
     * been discovered is a benign "not discovered yet" line; a coded refusal renders its code and message.
     */
    private void schemaOnline(List<String> words) {
        IdTableAndFormat parsed = parseIdTableAndFormat(words);
        if (parsed == null) {
            return;
        }
        final String connectionId = parsed.id();
        ConnectionSchemaOutcome outcome = withFailover(() ->
                controlPlane.schema(session.landingNode(), session.credential(), connectionId),
                o -> o instanceof ConnectionSchemaOutcome.Unreachable);
        switch (outcome) {
            case ConnectionSchemaOutcome.Found found -> {
                ConnectionSchema schema = found.schema();
                if (parsed.table() != null) {
                    ConnectionSchema narrowed = filterToTable(schema, parsed.table());
                    if (narrowed == null) {
                        PrintWriter err = commandLine.getErr();
                        err.println("schema: '" + parsed.table() + "' is not in the discovered model of '"
                                + connectionId + "' (tables: " + tableNames(schema) + ")");
                        err.flush();
                        return;
                    }
                    schema = narrowed;
                }
                renderSchema(schema, parsed.format());
            }
            case ConnectionSchemaOutcome.Absent ignored -> {
                PrintWriter err = commandLine.getErr();
                err.println("schema: '" + connectionId + "' has not been discovered yet");
                err.flush();
            }
            case ConnectionSchemaOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case ConnectionSchemaOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /** The model narrowed to one table by exact name, or {@code null} when the model has no such table. */
    private static ConnectionSchema filterToTable(ConnectionSchema schema, String table) {
        List<ConnectionSchema.Table> match = schema.tables().stream()
                .filter(t -> t.name().equals(table))
                .toList();
        return match.isEmpty()
                ? null
                : new ConnectionSchema(schema.connectionId(), schema.connectorId(), match, schema.discoveredAt());
    }

    /** The model's table names joined for a diagnostic line. */
    private static String tableNames(ConnectionSchema schema) {
        return schema.tables().stream().map(ConnectionSchema.Table::name)
                .reduce((a, b) -> a + ", " + b).orElse("none");
    }

    /**
     * {@code test-result <id> [-o text|json|yaml]} — reads a connection's latest stored test result and
     * renders it (the read peer of {@code test}, no probe run). A missing operand or an unknown option is a
     * benign usage line; a connection that has never been tested is a benign "not tested yet" line; a coded
     * refusal renders its code and message. The rendered report is the last test's — its outcome may itself
     * be a failure, which is a valid result to read back, not an error.
     */
    private void testResultOnline(List<String> words) {
        IdAndFormat parsed = parseIdAndFormat("test-result", words);
        if (parsed == null) {
            return;
        }
        final String connectionId = parsed.id();
        ConnectionTestResultOutcome outcome = withFailover(() ->
                controlPlane.testResult(session.landingNode(), session.credential(), connectionId),
                o -> o instanceof ConnectionTestResultOutcome.Unreachable);
        switch (outcome) {
            case ConnectionTestResultOutcome.Found found -> renderReport(found.report(), parsed.format());
            case ConnectionTestResultOutcome.Absent ignored -> {
                PrintWriter err = commandLine.getErr();
                err.println("test-result: '" + connectionId + "' has not been tested yet");
                err.flush();
            }
            case ConnectionTestResultOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case ConnectionTestResultOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * {@code register <path> [-o text|json|yaml]} — registers a local connector artifact with the server. A
     * file path uploads that one jar; a directory path uploads every {@code *.jar} directly under it as a
     * batch. The server introspects each artifact and stores it content-hash idempotently, then reports what
     * was registered (newly, or an already-registered no-op). A missing operand or unknown option is a benign
     * usage line; an unreadable path is a benign "cannot read" line; a coded refusal (a bad artifact, an id
     * conflict) renders its code and message, and on the machine surfaces an {@code {"error":{...}}} document.
     */
    private void registerOnline(List<String> words) {
        PathAndFormat parsed = parsePathAndFormat(words);
        if (parsed == null) {
            return;
        }
        Path artifactPath = workdir.resolve(parsed.path()).normalize();
        if (Files.isDirectory(artifactPath)) {
            registerDirectory(artifactPath, parsed.format());
            return;
        }
        PrintWriter err = commandLine.getErr();
        byte[] artifact;
        try {
            artifact = Files.readAllBytes(artifactPath);
        } catch (IOException e) {
            err.println("register: cannot read " + artifactPath + ": " + e.getMessage());
            err.flush();
            return;
        }
        echoUploading(artifactPath.getFileName().toString(), artifact.length, parsed.format());
        ConnectorRegisterOutcome outcome = withFailover(() -> controlPlane.register(
                session.landingNode(), session.credential(), artifact),
                o -> o instanceof ConnectorRegisterOutcome.Unreachable);
        switch (outcome) {
            case ConnectorRegisterOutcome.Registered registered -> renderRegistered(registered.connector(), parsed.format());
            case ConnectorRegisterOutcome.Rejected rejected -> renderRegisterRejection(rejected.code(), rejected.message(), parsed.format());
            case ConnectorRegisterOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * Parses {@code <path> [-o text|json|yaml]} for the register verb, printing its usage line to err and
     * returning {@code null} on any error. The single positional operand is the local artifact path to upload.
     */
    private PathAndFormat parsePathAndFormat(List<String> words) {
        PrintWriter err = commandLine.getErr();
        String path = null;
        OutputFormat format = OutputFormat.TEXT;
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            if (word.equals("-o") || word.equals("--output")) {
                if (i + 1 >= words.size()) {
                    err.println("register: " + word + " needs a format (text|json|yaml)");
                    err.flush();
                    return null;
                }
                OutputFormat chosen = outputFormat(words.get(++i));
                if (chosen == null) {
                    err.println("register: unknown output format '" + words.get(i) + "' (expected text|json|yaml)");
                    err.flush();
                    return null;
                }
                format = chosen;
            } else if (word.startsWith("-")) {
                err.println("register: unknown option " + word);
                err.flush();
                return null;
            } else if (path == null) {
                path = word;
            } else {
                err.println("register: too many operands (usage: register <path> [-o text|json|yaml])");
                err.flush();
                return null;
            }
        }
        if (path == null || path.isBlank()) {
            err.println("register: missing operand (usage: register <path> [-o text|json|yaml])");
            err.flush();
            return null;
        }
        return new PathAndFormat(path, format);
    }

    /** The parsed operands of the register verb: the local artifact path and the chosen output format. */
    private record PathAndFormat(String path, OutputFormat format) {
    }

    /** Renders a connector registration on the chosen surface: a human line, or the structured machine form. */
    private void renderRegistered(RegisteredConnector connector, OutputFormat format) {
        PrintWriter out = commandLine.getOut();
        switch (format) {
            case TEXT -> out.println(registeredHeadline(connector));
            case JSON -> out.println(JsonOut.write(registeredMap(connector)));
            case YAML -> out.println(YamlOut.write(registeredMap(connector)));
        }
        out.flush();
    }

    /** The human line: whether the artifact was newly registered or already present, then its id and hash. */
    private static String registeredHeadline(RegisteredConnector connector) {
        String state = connector.newlyRegistered() ? "registered" : "already registered";
        return state + "  " + connector.connectorId() + "  " + connector.contentHash();
    }

    /** The registration as an ordered tree for the machine surfaces, omitting a null PDK API version. */
    private static Map<String, Object> registeredMap(RegisteredConnector connector) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("connectorId", connector.connectorId());
        map.put("contentHash", connector.contentHash());
        putIfPresent(map, "pdkApiVersion", connector.pdkApiVersion());
        map.put("newlyRegistered", connector.newlyRegistered());
        return map;
    }

    /**
     * Renders a coded server refusal of a registration on the chosen surface. Text keeps the shared human
     * diagnostic (the {@code code} then the message, to err); the machine surfaces emit a structured
     * {@code {"error":{"code","message"}}} document to out, so {@code register -o json|yaml} stays
     * parseable even when the server refuses the artifact.
     */
    private void renderRegisterRejection(String code, String message, OutputFormat format) {
        if (format == OutputFormat.TEXT) {
            renderRejection(code, message);
            return;
        }
        PrintWriter out = commandLine.getOut();
        Map<String, Object> document = errorDocument(code, message);
        out.println(format == OutputFormat.JSON ? JsonOut.write(document) : YamlOut.write(document));
        out.flush();
    }

    /** A coded refusal wrapped as a machine document: {@code {"error":{code?,message}}}. */
    private static Map<String, Object> errorDocument(String code, String message) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("error", errorObject(code, message));
        return document;
    }

    /** The inner error object for the machine surfaces: the code (when present) then the message. */
    private static Map<String, Object> errorObject(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        putIfPresent(error, "code", code == null || code.isBlank() ? null : code);
        error.put("message", message);
        return error;
    }

    /**
     * Registers every {@code *.jar} directly under a directory: a case-insensitive, non-recursive scan in
     * filename order, uploading each artifact and collecting a per-artifact outcome. The batch stops early
     * once the server is unreachable (there is no point uploading the rest). The collected outcomes render
     * as a human report, or on the machine surfaces as an {@code {"artifacts":[...],"summary":{...}}} document.
     */
    private void registerDirectory(Path directory, OutputFormat format) {
        List<Path> jars;
        try (var entries = Files.list(directory)) {
            jars = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            PrintWriter err = commandLine.getErr();
            err.println("register: cannot read " + directory + ": " + e.getMessage());
            err.flush();
            return;
        }
        List<BatchEntry> outcomes = new ArrayList<>();
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            byte[] read;
            try {
                read = Files.readAllBytes(jar);
            } catch (IOException e) {
                outcomes.add(new BatchEntry.Unreadable(name, e.getMessage()));
                continue;   // one unreadable jar does not abort the rest of the batch
            }
            byte[] artifact = read;
            echoUploading(name, artifact.length, format);
            ConnectorRegisterOutcome outcome = withFailover(
                    () -> controlPlane.register(session.landingNode(), session.credential(), artifact),
                    o -> o instanceof ConnectorRegisterOutcome.Unreachable);
            outcomes.add(new BatchEntry.Attempted(name, outcome));
            if (outcome instanceof ConnectorRegisterOutcome.Unreachable) {
                break;   // failover found no healthy member; the server is gone, so stop uploading the rest
            }
        }
        renderBatch(directory, outcomes, jars.size(), format);
    }

    /** One artifact's place in a directory batch: uploaded (with the server's outcome) or unreadable locally. */
    private sealed interface BatchEntry {
        String artifact();

        record Attempted(String artifact, ConnectorRegisterOutcome outcome) implements BatchEntry {
        }

        record Unreadable(String artifact, String message) implements BatchEntry {
        }
    }

    /** The counts closing a batch report: newly registered, no-ops, coded refusals, unreachable, unreadable. */
    private record BatchCounts(int registered, int alreadyRegistered, int rejected, int unreachable, int unreadable) {
        static BatchCounts of(List<BatchEntry> outcomes) {
            int registered = 0;
            int alreadyRegistered = 0;
            int rejected = 0;
            int unreachable = 0;
            int unreadable = 0;
            for (BatchEntry entry : outcomes) {
                switch (entry) {
                    case BatchEntry.Unreadable ignored -> unreadable++;
                    case BatchEntry.Attempted attempted -> {
                        switch (attempted.outcome()) {
                            case ConnectorRegisterOutcome.Registered r -> {
                                if (r.connector().newlyRegistered()) {
                                    registered++;
                                } else {
                                    alreadyRegistered++;
                                }
                            }
                            case ConnectorRegisterOutcome.Rejected ignored -> rejected++;
                            case ConnectorRegisterOutcome.Unreachable ignored -> unreachable++;
                        }
                    }
                }
            }
            return new BatchCounts(registered, alreadyRegistered, rejected, unreachable, unreadable);
        }
    }

    /** Renders a directory batch on the chosen surface: a human report, or a machine artifacts/summary document. */
    private void renderBatch(Path directory, List<BatchEntry> outcomes, int scanned, OutputFormat format) {
        if (format == OutputFormat.TEXT) {
            renderBatchText(directory, outcomes, scanned);
            return;
        }
        PrintWriter out = commandLine.getOut();
        Map<String, Object> document = batchDocument(outcomes, scanned);
        out.println(format == OutputFormat.JSON ? JsonOut.write(document) : YamlOut.write(document));
        out.flush();
    }

    /** The human batch report: one line per artifact then a counts summary; an empty scan says so plainly. */
    private void renderBatchText(Path directory, List<BatchEntry> outcomes, int scanned) {
        PrintWriter out = commandLine.getOut();
        if (scanned == 0) {
            out.println("no connector jars found in " + directory);
            out.flush();
            return;
        }
        for (BatchEntry entry : outcomes) {
            out.println(batchLine(entry));
        }
        out.println(batchSummary(outcomes, scanned));
        out.flush();
    }

    /** One human report line for an artifact: its state and identity, its coded refusal, unreachable, or unreadable. */
    private static String batchLine(BatchEntry entry) {
        return switch (entry) {
            case BatchEntry.Unreadable unreadable -> unreadable.artifact() + "  error: cannot read  " + unreadable.message();
            case BatchEntry.Attempted attempted -> attempted.artifact() + "  " + attemptedLine(attempted.outcome());
        };
    }

    /** The state portion of a human batch line for an uploaded artifact. */
    private static String attemptedLine(ConnectorRegisterOutcome outcome) {
        return switch (outcome) {
            case ConnectorRegisterOutcome.Registered registered -> (registered.connector().newlyRegistered() ? "registered" : "already registered")
                    + "  " + registered.connector().connectorId() + "  " + registered.connector().contentHash();
            case ConnectorRegisterOutcome.Rejected rejected -> "error: " + rejected.code() + "  " + rejected.message();
            case ConnectorRegisterOutcome.Unreachable ignored -> "unreachable";
        };
    }

    /** The counts line closing a batch report: how many jars were attempted of those scanned, then a breakdown. */
    private static String batchSummary(List<BatchEntry> outcomes, int scanned) {
        BatchCounts counts = BatchCounts.of(outcomes);
        int notAttempted = scanned - outcomes.size();
        StringBuilder summary = new StringBuilder();
        if (notAttempted > 0) {
            summary.append(outcomes.size()).append(" of ").append(scanned).append(" artifacts attempted: ");
        } else {
            summary.append(scanned).append(" artifacts: ");
        }
        summary.append(counts.registered()).append(" registered, ")
                .append(counts.alreadyRegistered()).append(" no-op, ")
                .append(counts.rejected()).append(" rejected");
        if (counts.unreadable() > 0) {
            summary.append(", ").append(counts.unreadable()).append(" unreadable");
        }
        if (counts.unreachable() > 0) {
            summary.append(", ").append(counts.unreachable()).append(" unreachable");
        }
        if (notAttempted > 0) {
            summary.append("; ").append(notAttempted).append(" not attempted");
        }
        return summary.toString();
    }

    /** A directory batch as a machine document: an ordered {@code artifacts} array and a counts {@code summary}. */
    private static Map<String, Object> batchDocument(List<BatchEntry> outcomes, int scanned) {
        List<Object> artifacts = new ArrayList<>();
        for (BatchEntry entry : outcomes) {
            artifacts.add(batchRow(entry));
        }
        BatchCounts counts = BatchCounts.of(outcomes);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", scanned);
        summary.put("attempted", outcomes.size());
        summary.put("registered", counts.registered());
        summary.put("alreadyRegistered", counts.alreadyRegistered());
        summary.put("rejected", counts.rejected());
        if (counts.unreadable() > 0) {
            summary.put("unreadable", counts.unreadable());
        }
        if (counts.unreachable() > 0) {
            summary.put("unreachable", counts.unreachable());
        }
        int notAttempted = scanned - outcomes.size();
        if (notAttempted > 0) {
            summary.put("notAttempted", notAttempted);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("artifacts", artifacts);
        document.put("summary", summary);
        return document;
    }

    /** One artifact row for the machine batch document: the registration fields, or an {@code error} object. */
    private static Map<String, Object> batchRow(BatchEntry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("artifact", entry.artifact());
        switch (entry) {
            case BatchEntry.Unreadable unreadable -> row.put("error", errorObject(null, "cannot read: " + unreadable.message()));
            case BatchEntry.Attempted attempted -> {
                switch (attempted.outcome()) {
                    case ConnectorRegisterOutcome.Registered registered -> {
                        RegisteredConnector connector = registered.connector();
                        row.put("connectorId", connector.connectorId());
                        row.put("contentHash", connector.contentHash());
                        putIfPresent(row, "pdkApiVersion", connector.pdkApiVersion());
                        row.put("newlyRegistered", connector.newlyRegistered());
                    }
                    case ConnectorRegisterOutcome.Rejected rejected -> row.put("error", errorObject(rejected.code(), rejected.message()));
                    case ConnectorRegisterOutcome.Unreachable ignored -> row.put("error", errorObject(null, "the server is unreachable"));
                }
            }
        }
        return row;
    }

    /** In the human surface, announces an upload before it starts (name and size), so a large or bulk upload shows progress; the machine surfaces stay silent so their document is not polluted. */
    private void echoUploading(String artifact, long bytes, OutputFormat format) {
        if (format != OutputFormat.TEXT) {
            return;
        }
        PrintWriter err = commandLine.getErr();
        err.println("uploading " + artifact + " (" + humanSize(bytes) + ")");
        err.flush();
    }

    /** A short human byte size: {@code B} under a kibibyte, else one decimal in {@code KB}/{@code MB}/{@code GB}/{@code TB}. */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double size = bytes;
        int unit = -1;
        do {
            size /= 1024;
            unit++;
        } while (size >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", size, units[unit]);
    }

    /**
     * {@code connectors [-o text|json|yaml]} — lists the connectors the online catalog exposes (the bundled
     * snapshot union the connectors registered at runtime), each tagged bundled or registered. Takes no
     * operand; an unknown option is a benign usage line; a coded refusal renders its code and message.
     */
    private void connectorsOnline(List<String> words) {
        OutputFormat format = parseFormatOnly("connectors", words);
        if (format == null) {
            return;
        }
        ConnectorListOutcome outcome = withFailover(() ->
                controlPlane.connectorList(session.landingNode(), session.credential()),
                o -> o instanceof ConnectorListOutcome.Unreachable);
        switch (outcome) {
            case ConnectorListOutcome.Listed listed -> renderConnectors(listed.connectors(), format);
            case ConnectorListOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case ConnectorListOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * Parses {@code [-o text|json|yaml]} for a verb that takes no operand, printing its usage line to err
     * and returning {@code null} on any error (an unknown option or a stray operand). Defaults to TEXT.
     */
    private OutputFormat parseFormatOnly(String verb, List<String> words) {
        PrintWriter err = commandLine.getErr();
        OutputFormat format = OutputFormat.TEXT;
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            if (word.equals("-o") || word.equals("--output")) {
                if (i + 1 >= words.size()) {
                    err.println(verb + ": " + word + " needs a format (text|json|yaml)");
                    err.flush();
                    return null;
                }
                OutputFormat chosen = outputFormat(words.get(++i));
                if (chosen == null) {
                    err.println(verb + ": unknown output format '" + words.get(i) + "' (expected text|json|yaml)");
                    err.flush();
                    return null;
                }
                format = chosen;
            } else {
                err.println(verb + ": takes no operand (usage: " + verb + " [-o text|json|yaml])");
                err.flush();
                return null;
            }
        }
        return format;
    }

    /** Renders the connector catalog on the chosen surface: one human line per connector, or the machine tree. */
    private void renderConnectors(List<CatalogConnector> connectors, OutputFormat format) {
        PrintWriter out = commandLine.getOut();
        switch (format) {
            case TEXT -> {
                if (connectors.isEmpty()) {
                    out.println("no connectors");
                } else {
                    for (CatalogConnector connector : connectors) {
                        out.println(connectorHeadline(connector));
                    }
                }
            }
            case JSON -> out.println(JsonOut.write(connectorsMap(connectors)));
            case YAML -> out.println(YamlOut.write(connectorsMap(connectors)));
        }
        out.flush();
    }

    /** The human line: origin, group, id, the modes it may be paired with, and whether it can sink. */
    private static String connectorHeadline(CatalogConnector connector) {
        String modes = connector.modes().isEmpty() ? "-" : String.join(",", connector.modes());
        String sink = connector.sink() ? "sink" : "no-sink";
        return connector.origin() + "  " + connector.group() + "  " + connector.id() + "  [" + modes + "]  " + sink;
    }

    /** The connector list as an ordered tree for the machine surfaces, omitting null name / group / origin. */
    private static Map<String, Object> connectorsMap(List<CatalogConnector> connectors) {
        List<Object> rows = new ArrayList<>();
        for (CatalogConnector connector : connectors) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", connector.id());
            putIfPresent(row, "name", connector.name());
            putIfPresent(row, "group", connector.group());
            row.put("modes", connector.modes());
            row.put("sink", connector.sink());
            putIfPresent(row, "origin", connector.origin());
            rows.add(row);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("connectors", rows);
        return map;
    }

    /**
     * Parses {@code <id> [-o text|json|yaml]} for the report verbs that self-parse the output flag, printing
     * the matching usage line to err and returning {@code null} on any error. The verb name is threaded
     * through so each verb's messages name it (routed before the positional-only guard the other verbs share).
     */
    private IdAndFormat parseIdAndFormat(String verb, List<String> words) {
        PrintWriter err = commandLine.getErr();
        String id = null;
        OutputFormat format = OutputFormat.TEXT;
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            if (word.equals("-o") || word.equals("--output")) {
                if (i + 1 >= words.size()) {
                    err.println(verb + ": " + word + " needs a format (text|json|yaml)");
                    err.flush();
                    return null;
                }
                OutputFormat chosen = outputFormat(words.get(++i));
                if (chosen == null) {
                    err.println(verb + ": unknown output format '" + words.get(i) + "' (expected text|json|yaml)");
                    err.flush();
                    return null;
                }
                format = chosen;
            } else if (word.startsWith("-")) {
                err.println(verb + ": unknown option " + word);
                err.flush();
                return null;
            } else if (id == null) {
                id = word;
            } else {
                err.println(verb + ": too many operands (usage: " + verb + " <id> [-o text|json|yaml])");
                err.flush();
                return null;
            }
        }
        if (id == null || id.isBlank()) {
            err.println(verb + ": missing operand (usage: " + verb + " <id> [-o text|json|yaml])");
            err.flush();
            return null;
        }
        return new IdAndFormat(id, format);
    }

    /** The parsed operands of a report verb: the connection id and the chosen output format. */
    private record IdAndFormat(String id, OutputFormat format) {
    }

    /**
     * Parses {@code <id> [table] [-o text|json|yaml]} for the schema read verb, printing its usage line to
     * err and returning {@code null} on any error. The second positional operand is the optional table to
     * narrow the view to.
     */
    private IdTableAndFormat parseIdTableAndFormat(List<String> words) {
        PrintWriter err = commandLine.getErr();
        String id = null;
        String table = null;
        OutputFormat format = OutputFormat.TEXT;
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            if (word.equals("-o") || word.equals("--output")) {
                if (i + 1 >= words.size()) {
                    err.println("schema: " + word + " needs a format (text|json|yaml)");
                    err.flush();
                    return null;
                }
                OutputFormat chosen = outputFormat(words.get(++i));
                if (chosen == null) {
                    err.println("schema: unknown output format '" + words.get(i) + "' (expected text|json|yaml)");
                    err.flush();
                    return null;
                }
                format = chosen;
            } else if (word.startsWith("-")) {
                err.println("schema: unknown option " + word);
                err.flush();
                return null;
            } else if (id == null) {
                id = word;
            } else if (table == null) {
                table = word;
            } else {
                err.println("schema: too many operands (usage: schema <id> [table] [-o text|json|yaml])");
                err.flush();
                return null;
            }
        }
        if (id == null || id.isBlank()) {
            err.println("schema: missing operand (usage: schema <id> [table] [-o text|json|yaml])");
            err.flush();
            return null;
        }
        return new IdTableAndFormat(id, table, format);
    }

    /** The parsed operands of the schema verb: the connection id, the optional table, and the format. */
    private record IdTableAndFormat(String id, String table, OutputFormat format) {
    }

    /** The {@code -o} format spelled text / json / yaml (case-insensitive), or {@code null} if unrecognised. */
    private static OutputFormat outputFormat(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "text" -> OutputFormat.TEXT;
            case "json" -> OutputFormat.JSON;
            case "yaml" -> OutputFormat.YAML;
            default -> null;
        };
    }

    /** Renders a connection report on the chosen surface: a human summary, or the structured machine form. */
    private void renderReport(ConnectionReport report, OutputFormat format) {
        PrintWriter out = commandLine.getOut();
        switch (format) {
            case TEXT -> renderReportText(out, report);
            case JSON -> out.println(JsonOut.write(reportMap(report)));
            case YAML -> out.println(YamlOut.write(reportMap(report)));
        }
        out.flush();
    }

    /** The human summary: an outcome header naming the connection + connector, then one line per check. */
    private static void renderReportText(PrintWriter out, ConnectionReport report) {
        out.println(report.outcome() + "  " + report.connectionId() + " (" + report.connectorId() + ")");
        for (ConnectionReport.Check check : report.checks()) {
            StringBuilder line = new StringBuilder(String.format("  %-7s %s", check.status(), check.name()));
            if (check.message() != null && !check.message().isBlank()) {
                line.append("  ").append(check.message());
            }
            out.println(line);
        }
    }

    /** The report as an ordered tree for the machine surfaces, omitting the optional check fields left null. */
    private static Map<String, Object> reportMap(ConnectionReport report) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("connectionId", report.connectionId());
        map.put("connectorId", report.connectorId());
        map.put("outcome", report.outcome());
        List<Object> checks = new ArrayList<>();
        for (ConnectionReport.Check check : report.checks()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", check.name());
            entry.put("status", check.status());
            putIfPresent(entry, "message", check.message());
            putIfPresent(entry, "reason", check.reason());
            putIfPresent(entry, "solution", check.solution());
            putIfPresent(entry, "connectorErrorCode", check.connectorErrorCode());
            checks.add(entry);
        }
        map.put("checks", checks);
        map.put("testedAt", report.testedAt());
        return map;
    }

    /** Puts a string value under {@code key} only when it is present (non-null, non-blank). */
    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /** Renders a discovered model on the chosen surface: a human summary, or the structured machine form. */
    private void renderSchema(ConnectionSchema schema, OutputFormat format) {
        PrintWriter out = commandLine.getOut();
        switch (format) {
            case TEXT -> renderSchemaText(out, schema);
            case JSON -> out.println(JsonOut.write(schemaMap(schema)));
            case YAML -> out.println(YamlOut.write(schemaMap(schema)));
        }
        out.flush();
    }

    /**
     * The human summary: a header naming the connection + connector and the table count, then each table.
     * A single-table view (the narrowed {@code schema <id> <table>} form) expands the fields, primary-key
     * markers and indexes; the multi-table view keeps to one summary line per table.
     */
    private static void renderSchemaText(PrintWriter out, ConnectionSchema schema) {
        List<ConnectionSchema.Table> tables = schema.tables();
        out.println(schema.connectionId() + " (" + schema.connectorId() + ")  "
                + tables.size() + (tables.size() == 1 ? " table" : " tables"));
        if (tables.size() == 1) {
            renderTableDetail(out, tables.get(0));
            return;
        }
        for (ConnectionSchema.Table table : tables) {
            StringBuilder line = new StringBuilder(String.format("  %-20s %d %s", table.name(),
                    table.fields().size(), table.fields().size() == 1 ? "field" : "fields"));
            if (!table.primaryKey().isEmpty()) {
                line.append("  pk(").append(String.join(", ", table.primaryKey())).append(')');
            }
            out.println(line);
        }
    }

    /** One table expanded under its name: each field with its type and pk marker, then each index. */
    private static void renderTableDetail(PrintWriter out, ConnectionSchema.Table table) {
        out.println("  " + table.name());
        for (ConnectionSchema.Field field : table.fields()) {
            StringBuilder line = new StringBuilder(String.format("    %-20s %s",
                    field.name(), field.type() == null ? "?" : field.type()));
            if (table.primaryKey().contains(field.name())) {
                line.append("  pk");
            }
            out.println(line);
        }
        for (ConnectionSchema.Index index : table.indexes()) {
            StringBuilder line = new StringBuilder(
                    "    index " + index.name() + " (" + String.join(", ", index.fields()) + ")");
            if (index.unique()) {
                line.append("  unique");
            }
            out.println(line);
        }
    }

    /** The model as an ordered tree for the machine surfaces, omitting a field type left unresolved. */
    private static Map<String, Object> schemaMap(ConnectionSchema schema) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("connectionId", schema.connectionId());
        map.put("connectorId", schema.connectorId());
        List<Object> tables = new ArrayList<>();
        for (ConnectionSchema.Table table : schema.tables()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", table.name());
            List<Object> fields = new ArrayList<>();
            for (ConnectionSchema.Field field : table.fields()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", field.name());
                putIfPresent(f, "type", field.type());
                fields.add(f);
            }
            entry.put("fields", fields);
            entry.put("primaryKey", table.primaryKey());
            List<Object> indexes = new ArrayList<>();
            for (ConnectionSchema.Index index : table.indexes()) {
                Map<String, Object> i = new LinkedHashMap<>();
                i.put("name", index.name());
                i.put("fields", index.fields());
                i.put("unique", index.unique());
                indexes.add(i);
            }
            entry.put("indexes", indexes);
            tables.add(entry);
        }
        map.put("tables", tables);
        map.put("discoveredAt", schema.discoveredAt());
        return map;
    }

    /**
     * {@code status <pipeline-id>} — reads the pipeline's lifecycle state from the server and prints
     * {@code <id>  <state>}. A missing id is a benign usage line; a pipeline that has published no
     * observation is a coded refusal ({@code monitor.no-observation}) rendering its code and message.
     */
    private void statusOnline(List<String> words) {
        String id = readTargetId(words);
        if (id == null) {
            return;
        }
        StatusOutcome outcome = withFailover(() ->
                controlPlane.status(session.landingNode(), session.credential(), id),
                o -> o instanceof StatusOutcome.Unreachable);
        PrintWriter out = commandLine.getOut();
        switch (outcome) {
            case StatusOutcome.Found found -> {
                out.println(found.pipelineId() + "  " + found.state().toLowerCase(Locale.ROOT));
                out.flush();
            }
            case StatusOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case StatusOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * {@code metrics <pipeline-id>} — reads the pipeline's open map of run statistics and its per-table source
     * positions and prints one {@code <name>  <value>} line each in name order (a per-table position under a
     * {@code perTableOffset.<table>} key), or a benign {@code no metrics} line when none are wired yet
     * (unavailable, never faked). A coded refusal renders its code and message.
     */
    private void metricsOnline(List<String> words) {
        String id = readTargetId(words);
        if (id == null) {
            return;
        }
        MetricsOutcome outcome = withFailover(() ->
                controlPlane.metrics(session.landingNode(), session.credential(), id),
                o -> o instanceof MetricsOutcome.Unreachable);
        PrintWriter out = commandLine.getOut();
        switch (outcome) {
            case MetricsOutcome.Found found -> {
                Map<String, String> lines = new TreeMap<>();
                found.metrics().forEach((name, value) -> lines.put(name, String.valueOf(value)));
                found.perTableOffset().forEach((table, position) -> lines.put("perTableOffset." + table, position));
                if (lines.isEmpty()) {
                    out.println("no metrics");
                } else {
                    lines.forEach((name, value) -> out.println(name + "  " + value));
                }
                out.flush();
            }
            case MetricsOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case MetricsOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /**
     * {@code snapshot <pipeline-id>} — reads the pipeline's per-table initial-load progress and prints one
     * {@code <table>  <rowsDone>/<rowsTotal> (<pct>%)} line per table in name order (a table with no total
     * shows {@code <rowsDone>/?} — honest partial data), or a benign {@code no snapshot} line when there is
     * none. A coded refusal renders its code and message.
     */
    private void snapshotOnline(List<String> words) {
        String id = readTargetId(words);
        if (id == null) {
            return;
        }
        SnapshotOutcome outcome = withFailover(() ->
                controlPlane.snapshot(session.landingNode(), session.credential(), id),
                o -> o instanceof SnapshotOutcome.Unreachable);
        PrintWriter out = commandLine.getOut();
        switch (outcome) {
            case SnapshotOutcome.Found found -> {
                if (found.tables().isEmpty()) {
                    out.println("no snapshot");
                } else {
                    new TreeMap<>(found.tables()).forEach((table, progress) ->
                            out.println(table + "  " + renderProgress(progress)));
                }
                out.flush();
            }
            case SnapshotOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case SnapshotOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    private void logsOnline(List<String> words) {
        String id = readTargetId(words);
        if (id == null) {
            return;
        }
        LogsOutcome outcome = withFailover(() ->
                controlPlane.logs(session.landingNode(), session.credential(), id),
                o -> o instanceof LogsOutcome.Unreachable);
        PrintWriter out = commandLine.getOut();
        switch (outcome) {
            case LogsOutcome.Found found -> {
                if (found.lines().isEmpty()) {
                    out.println("no logs");
                } else {
                    found.lines().forEach(line -> out.println(renderLogLine(line)));
                }
                out.flush();
            }
            case LogsOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
            case LogsOutcome.Unreachable ignored -> reportRequestFailed();
        }
    }

    /** One tailed line as {@code <iso-timestamp> <level> <message>}. */
    private static String renderLogLine(RemoteLogLine line) {
        return Instant.ofEpochMilli(line.timestampMillis()) + "  " + line.level() + "  " + line.message();
    }

    /**
     * {@code status <pipeline-id> --watch} — streams the pipeline's lifecycle state and each subsequent
     * change over the websocket, printing {@code <id>  <state>} per frame, until the connection ends or the
     * user interrupts (Ctrl-C). A missing id is a benign usage line. The state stream re-attaches across a
     * dropped connection; nothing is printed until the pipeline has published an observation.
     */
    private void statusWatch(List<String> words) {
        String id = streamTargetId(words, "--watch");
        if (id == null) {
            return;
        }
        PrintWriter out = commandLine.getOut();
        streamCancelled = false;
        controlPlane.watchStatus(session.landingNode(), session.credential(), id,
                (pipelineId, state) -> {
                    out.println(pipelineId + "  " + state.toLowerCase(Locale.ROOT));
                    out.flush();
                },
                this::isStreamCancelled);
    }

    /**
     * {@code logs <pipeline-id> --follow} — streams the pipeline's node-local log tail and each newly
     * appended line over the websocket ({@code tail -f}), until the connection ends or the user interrupts
     * (Ctrl-C). A missing id is a benign usage line.
     */
    private void logsFollow(List<String> words) {
        String id = streamTargetId(words, "--follow");
        if (id == null) {
            return;
        }
        PrintWriter out = commandLine.getOut();
        streamCancelled = false;
        controlPlane.followLogs(session.landingNode(), session.credential(), id,
                (pipelineId, lines) -> {
                    lines.forEach(line -> out.println(renderLogLine(line)));
                    out.flush();
                },
                this::isStreamCancelled);
    }

    /**
     * The pipeline id operand for a streaming verb ({@code <verb> <pipeline-id> <flag>}), the {@code flag}
     * ignored wherever it appears; or {@code null} after a benign line when the id is missing or an
     * unsupported option is present.
     */
    private String streamTargetId(List<String> words, String flag) {
        PrintWriter err = commandLine.getErr();
        String id = null;
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            if (word.equals(flag)) {
                continue;
            }
            if (word.startsWith("-")) {
                err.println(words.get(0) + ": options are not supported on a connected verb yet");
                err.flush();
                return null;
            }
            if (id == null) {
                id = word;
            }
        }
        if (id == null || id.isBlank()) {
            err.println(words.get(0) + ": missing operand (usage: " + words.get(0) + " <pipeline-id> " + flag + ")");
            err.flush();
            return null;
        }
        return id;
    }

    /**
     * The pipeline id operand shared by the observation read verbs ({@code <verb> <pipeline-id>}), or
     * {@code null} after a benign usage line when it is missing — a read names exactly one pipeline.
     */
    private String readTargetId(List<String> words) {
        if (words.size() < 2 || words.get(1).isBlank()) {
            PrintWriter err = commandLine.getErr();
            err.println(words.get(0) + ": missing operand (usage: " + words.get(0) + " <pipeline-id>)");
            err.flush();
            return null;
        }
        return words.get(1);
    }

    /**
     * One table's snapshot progress: {@code rowsDone/rowsTotal (donePct%)} when the total is known, or
     * {@code rowsDone/?} when it is unavailable — honest partial data, never faked as a percentage.
     */
    private static String renderProgress(RemoteTableSnapshot progress) {
        if (progress.rowsTotal() != null && progress.donePct() != null) {
            return progress.rowsDone() + "/" + progress.rowsTotal() + " (" + progress.donePct() + "%)";
        }
        return progress.rowsDone() + "/?";
    }

    /**
     * Runs an online call, and if the landing node could not answer it, fails over to another member and
     * retries the call once against the new landing node. When {@link #failover} cannot re-land, the
     * original unreachable outcome is returned (and the session is now offline). The unreachable predicate
     * lets one wrapper serve every verb's distinct sealed outcome type.
     */
    private <T> T withFailover(Supplier<T> call, Predicate<T> unreachable) {
        T outcome = call.get();
        if (unreachable.test(outcome) && failover()) {
            return call.get();
        }
        return outcome;
    }

    /**
     * Reads every {@code *.cyn.yml} under a path (recursively for a directory) as drafts, in name order,
     * with each file's {@code ${...}} references substituted from this session's environment.
     *
     * <p>Substituting here, rather than letting the server do it, is what keeps the variables read the
     * author's own: this side loads the files, so this side resolves them, and only values cross the
     * wire. The drafts stay raw text otherwise — the server remains the only parser.
     */
    private List<LocalDraft> collectDrafts(Path target) throws IOException {
        List<LocalDraft> drafts = new ArrayList<>();
        if (Files.isDirectory(target)) {
            try (var files = Files.walk(target)) {
                List<Path> yamls = files.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().endsWith(".cyn.yml"))
                        .sorted()
                        .toList();
                for (Path f : yamls) {
                    drafts.add(draft(target.relativize(f).toString(), f));
                }
            } catch (UncheckedIOException e) {
                // Files.walk surfaces a mid-traversal access error (an unreadable or concurrently-removed
                // subdirectory) as an unchecked wrapper thrown from the terminal operation; normalize it to
                // the checked IOException the caller renders as a benign "cannot read" line rather than
                // letting it escape and crash the read loop.
                throw e.getCause() != null ? e.getCause() : new IOException(e.getMessage(), e);
            }
        } else if (Files.isRegularFile(target)) {
            drafts.add(draft(target.getFileName().toString(), target));
        }
        return drafts;
    }

    /** Reads one artifact and resolves its references, naming the file on whatever it refuses. */
    private LocalDraft draft(String source, Path file) throws IOException {
        String text = Files.readString(file);
        try {
            return new LocalDraft(source, Interpolator.interpolate(text, env));
        } catch (DslException e) {
            throw e.withSource(source);
        }
    }

    /**
     * Renders a coded refusal raised on this side of the wire, located at the file and line it was found
     * on. Distinct from {@link #renderRejection} only in where the message comes from: a server refusal
     * arrives rendered, while this one is rendered here from the code and its arguments.
     */
    private void renderLocalRefusal(DslException e) {
        MessageCatalog.Rendered rendered = MessageCatalog.bundled().render(e.code(), e.args());
        PrintWriter err = commandLine.getErr();
        String at = e.source() + (e.line() > 0 ? ":" + e.line() + ":" + e.column() : "");
        err.println(Ansi.AUTO.string("@|bold,red error:|@") + " " + at + "  " + e.code().code());
        err.println("  " + rendered.message());
        if (rendered.solution() != null) {
            err.println("  " + rendered.solution());
        }
        err.flush();
    }

    /** Renders a coded server refusal: the {@code code} (when present) then the rendered message, to err. */
    private void renderRejection(String code, String message) {
        PrintWriter err = commandLine.getErr();
        if (!code.isBlank()) {
            err.println(Ansi.AUTO.string("@|bold,red error:|@") + " " + code);
        }
        err.println("  " + message);
        err.flush();
    }

    /**
     * Reports that a request could not be completed after failover. Only reached when the retry itself was
     * unreachable while a landing node is still held; a total loss of the cluster has already been reported
     * by {@link #failover} (which then took the session offline), so this stays silent in that case.
     */
    private void reportRequestFailed() {
        if (!session.isConnected()) {
            return;   // failover already reported the connection loss and went offline
        }
        PrintWriter err = commandLine.getErr();
        err.println("request failed: " + hostPort(session.landingNode()) + " is unreachable");
        err.flush();
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
            Diagnostics.printText(err, CliError.NOT_CONNECTED, Map.of("verb", "login"));
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
            case LoginOutcome.Rejected rejected -> renderRejection(rejected.code(), rejected.message());
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

    /** Renders the {@code cli.connect-failed} diagnostic through the shared coded-error renderer. */
    private void reportConnectFailed(List<URI> seeds) {
        String display = seeds.stream().map(URI::toString).collect(Collectors.joining(", "));
        Diagnostics.printText(commandLine.getErr(), CliError.CONNECT_FAILED, Map.of("seeds", display));
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
            // Ctrl-C stops an in-flight watch/follow stream. The line reader saves and restores the signal
            // handlers around readLine (where Ctrl-C stays "clear the line"), so this handler is active only
            // while a dispatched verb runs -- exactly when a stream is blocking the loop.
            terminal.handle(Terminal.Signal.INT, signal -> cancelStream());
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
