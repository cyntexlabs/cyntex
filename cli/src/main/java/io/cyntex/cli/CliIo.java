package io.cyntex.cli;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;

/**
 * Resolves a subcommand's output writers to the ones configured on the top-level command. picocli
 * does not copy a {@code setOut} / {@code setErr} writer down into every subcommand's own
 * CommandLine, so a subcommand that prints via its own {@code spec.commandLine().getOut()} would
 * miss a writer the caller installed on the root. Walking to the root makes both the production
 * console streams and the test-installed capture streams resolve identically.
 */
final class CliIo {

    private CliIo() {
    }

    static PrintWriter out(CommandSpec spec) {
        return root(spec).getOut();
    }

    static PrintWriter err(CommandSpec spec) {
        return root(spec).getErr();
    }

    private static CommandLine root(CommandSpec spec) {
        CommandLine cl = spec.commandLine();
        while (cl.getParent() != null) {
            cl = cl.getParent();
        }
        return cl;
    }
}
