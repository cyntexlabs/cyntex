package io.cyntex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * The workspace-root option shared by the authoring verbs. The workspace is a directory holding the
 * {@code *.cyn.yml} artifacts a user is editing, laid out by kind ({@code <root>/<kind>/<id>.cyn.yml}).
 * Resolution order is flag &gt; {@code CYNTEX_WORKDIR} env &gt; the conventional {@code cyn-work}, all
 * encoded in the picocli default so the option carries the precedence itself.
 *
 * <p>Mixed into each verb that operates on a workspace; the REPL injects the session workspace by
 * passing {@code --workdir} on the dispatched line.
 */
final class WorkspaceOption {

    @Option(names = {"-w", "--workdir"}, paramLabel = "DIR",
            defaultValue = "${env:CYNTEX_WORKDIR:-cyn-work}",
            description = "Workspace root directory (default: cyn-work, or $CYNTEX_WORKDIR).")
    String workdir;

    /** The resolved workspace root as a path; never null thanks to the default. */
    Path root() {
        return Path.of(workdir);
    }

    /**
     * Resolves the workspace root from top-level args using this option's own precedence
     * (flag &gt; {@code CYNTEX_WORKDIR} &gt; {@code cyn-work}). Used to seed the REPL when {@code cyntex}
     * is launched with no verb: the same default expression drives both the verbs and the seed, so they
     * cannot drift. Throws if the args are malformed (e.g. a {@code -w} with no value).
     */
    static Path resolve(String... args) {
        WorkspaceOption option = new WorkspaceOption();
        new CommandLine(option).parseArgs(args);
        return option.root();
    }
}
