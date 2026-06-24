package io.cyntex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.List;

/**
 * The Cyntex CLI: the surface-ring product front-end. Dual-mode — bare {@code cyntex} opens the
 * offline REPL; one-shot subcommands share the same verb table for scripting / AI.
 *
 * <p>Offline verbs are a whitelist: {@code validate} / {@code new} / {@code explain} / {@code ls} /
 * {@code desc} run fully without any server. The server-state verbs are registered too (so they are
 * discoverable and report a clear "requires a connection" rather than going missing), but in this
 * offline build they never reach a service — the CLI talks to a running Cyntex over HTTP only (rule R6).
 */
@Command(name = "cyntex", mixinStandardHelpOptions = true, version = "cyntex 0.1.0",
        subcommands = {ValidateCmd.class, NewCmd.class, ExplainCmd.class, LsCmd.class, DescCmd.class},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:success",
                "1:a coded diagnostic was reported (an invalid workspace, or a refused operation)",
                "2:usage error (bad arguments, or a path that is not a usable workspace)",
                "3:the verb needs a server connection (this build is offline)"
        })
public final class Cli implements Runnable {

    /** Exit code for a server-state verb invoked in this offline build. */
    static final int EXIT_OFFLINE_ONLY = 3;

    /**
     * The offline verb whitelist — the single source of truth for which verbs run without a server.
     * Both the recovery hint a connected verb prints and the registration-vs-whitelist guard test
     * read it, so the user-facing list can never drift from the verbs actually wired up.
     */
    static final List<String> OFFLINE_VERBS = List.of("validate", "new", "explain", "ls", "desc");

    /**
     * Verbs that need a live server connection. Registered so they are listed and explained rather
     * than reported as unknown; each one prints a "requires a connection" notice in this offline
     * build (the connected behaviour belongs to a later, server-state slice).
     */
    static final List<String> CONNECTED_VERBS = List.of(
            "connect", "apply", "run", "export", "diff", "edit", "start", "stop", "status", "logs");

    @Spec
    CommandSpec spec;

    /** Builds the shared command table used by both one-shot mode and the REPL. */
    public static CommandLine newCommandLine() {
        CommandLine commandLine = new CommandLine(new Cli());
        for (String verb : CONNECTED_VERBS) {
            commandLine.addSubcommand(verb, new ConnectedVerb());
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
            new Repl(newCommandLine(), seed).run();
        } else {
            System.exit(newCommandLine().execute(args));
        }
    }
}
