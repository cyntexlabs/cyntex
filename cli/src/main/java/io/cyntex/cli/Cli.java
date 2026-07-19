package io.cyntex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The Cyntex CLI: the surface-ring product front-end. Dual-mode — bare {@code cyntex} opens the
 * offline REPL; one-shot subcommands share the same verb table for scripting / AI.
 *
 * <p>Offline verbs are a whitelist: {@code validate} / {@code new} / {@code explain} / {@code ls} /
 * {@code desc} run fully without any server. The server-state verbs are registered too, so they are
 * discoverable and report a coded "not connected" diagnostic rather than going missing; they reach a
 * service through the REPL, where a connection is established and held — the CLI talks to a running
 * Cyntex over HTTP only (rule R6).
 */
@Command(name = "cyntex", mixinStandardHelpOptions = true, version = "cyntex 0.1.0",
        subcommands = {ValidateCmd.class, NewCmd.class, ExplainCmd.class, LsCmd.class, DescCmd.class},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:success",
                "1:a coded diagnostic was reported (an invalid workspace, or a refused operation)",
                "2:usage error (bad arguments, or a path that is not a usable workspace)",
                "3:the verb is unavailable here (it needs a connection, or is not implemented yet)"
        })
public final class Cli implements Runnable {

    /** Exit code for a verb that cannot run as invoked: no connection yet, or no implementation yet. */
    static final int EXIT_VERB_UNAVAILABLE = 3;

    /**
     * The offline verb whitelist — the single source of truth for which verbs run without a server.
     * The connected-verb derivation below and the registration-vs-whitelist guard test both read it,
     * so the declared list can never drift from the verbs actually wired up.
     */
    static final List<String> OFFLINE_VERBS = List.of("validate", "new", "explain", "ls", "desc");

    /**
     * How this face spells each operation it projects: operation id → verb name. The spelling is not
     * derivable from the id ({@code artifact.list} is {@code ls}, {@code connector.list} is
     * {@code connectors}), so the projection is declared rather than computed — this map is what the
     * face-projection gate checks against the operation registry in both directions, and the command
     * table below is built from it, so a verb cannot be projected here and left unregistered.
     *
     * <p>Operation ids are plain strings: the CLI reaches a server over HTTP and must not link the
     * control ring to read them (rule R6).
     */
    public static final Map<String, String> VERB_BY_OPERATION = Map.ofEntries(
            Map.entry("artifact.apply", "apply"),
            Map.entry("artifact.get", "get"),
            Map.entry("artifact.list", "ls"),
            Map.entry("connection.test", "test"),
            Map.entry("connection.test-result", "test-result"),
            Map.entry("connection.discover-schema", "discover-schema"),
            Map.entry("connection.schema", "schema"),
            Map.entry("connector.register", "register"),
            Map.entry("connector.list", "connectors"),
            Map.entry("pipeline.start", "start"),
            Map.entry("pipeline.stop", "stop"),
            Map.entry("pipeline.pause", "pause"),
            Map.entry("pipeline.resume", "resume"),
            Map.entry("pipeline.status", "status"),
            Map.entry("pipeline.metrics", "metrics"),
            Map.entry("pipeline.snapshot", "snapshot"),
            Map.entry("pipeline.logs", "logs"));

    /**
     * Verbs that chain several registered operations rather than projecting one ({@code run} is apply
     * then start), and are not implemented yet. They are registered so they stay discoverable, but they
     * report that they do not exist yet — not that a connection is missing, which would be false in both
     * states: connecting does not implement them.
     */
    static final List<String> UNIMPLEMENTED_COMPOSITE_VERBS = List.of("run", "export", "diff", "edit");

    /**
     * Verbs that need a live server connection — every projected verb that does not also run offline
     * ({@code ls} browses the local workspace when there is no session). Derived from the projection
     * above so the two can never disagree. Registered so they are listed and explained rather than
     * reported as unknown, and each reports a coded "not connected" diagnostic until a session exists.
     * {@code connect} is not here — it is a REPL builtin, since a connection is session-scoped and
     * meaningful only inside the read loop, not as a one-shot verb.
     */
    static final List<String> CONNECTED_VERBS = VERB_BY_OPERATION.values().stream()
            .filter(verb -> !OFFLINE_VERBS.contains(verb))
            .sorted()
            .toList();

    @Spec
    CommandSpec spec;

    /** Builds the shared command table used by both one-shot mode and the REPL. */
    public static CommandLine newCommandLine() {
        CommandLine commandLine = new CommandLine(new Cli());
        for (String verb : CONNECTED_VERBS) {
            commandLine.addSubcommand(verb, new ConnectedVerb());
        }
        for (String verb : UNIMPLEMENTED_COMPOSITE_VERBS) {
            commandLine.addSubcommand(verb, new UnimplementedVerb());
        }
        // accept -o json / -o JSON alike; the lower-case forms are the documented spelling
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        return commandLine;
    }

    /** Invoked when {@code cyntex} runs with no subcommand under {@code execute}; prints usage. */
    @Override
    public void run() {
        spec.commandLine().usage(CliIo.out(spec));
    }

    /**
     * Whether these top-level args open the REPL rather than running a one-shot verb. A bare invocation,
     * or one carrying only the workspace option ({@code -w DIR} / {@code --workdir DIR} / {@code =} form),
     * seeds and opens the REPL. The first token that is a verb, a help/version request, or anything else
     * is a one-shot the command table parses and (if malformed) rejects with a loud usage error — the
     * top-level table declares no {@code -w}, so {@code cyntex -w foo validate} is never silently ignored.
     */
    static boolean isReplLaunch(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-w") || a.equals("--workdir")) {
                i++;   // skip the option's value
                continue;
            }
            if (a.startsWith("-w=") || a.startsWith("--workdir=")) {
                continue;
            }
            return false;
        }
        return true;
    }

    public static void main(String[] args) {
        if (isReplLaunch(args)) {
            Path seed;
            try {
                seed = WorkspaceOption.resolve(args);
            } catch (RuntimeException e) {
                // malformed workspace flags (e.g. a -w with no value): let the command table render it
                System.exit(newCommandLine().execute(args));
                return;
            }
            new Repl(newCommandLine(), seed, new HttpControlPlaneClient()).run();
        } else {
            System.exit(newCommandLine().execute(args));
        }
    }
}
