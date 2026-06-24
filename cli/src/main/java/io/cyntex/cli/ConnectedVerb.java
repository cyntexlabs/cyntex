package io.cyntex.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

/**
 * The handler shared by every server-state verb (registered under each name in {@link Cli}). In
 * this offline build the verb is known but unreachable, so it reports that a connection is required
 * — a discoverable affordance, not a missing command. The actual connected behaviour belongs to a
 * later server-state slice.
 */
@Command(description = "(requires a connection to a Cyntex server)")
final class ConnectedVerb implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter err = CliIo.err(spec);
        err.println("verb '" + spec.name() + "' requires a connection to a Cyntex server; "
                + "this build is offline.");
        err.println("offline verbs: " + String.join(", ", Cli.OFFLINE_VERBS) + ".");
        err.flush();
        return Cli.EXIT_OFFLINE_ONLY;
    }
}
