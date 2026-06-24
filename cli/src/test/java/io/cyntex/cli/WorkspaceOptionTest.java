package io.cyntex.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared workspace-root option. Resolution precedence (flag &gt; {@code CYNTEX_WORKDIR} &gt; the
 * {@code cyn-work} default) lives in the picocli default expression; these lock the flag and default
 * branches. The env branch is exercised by the native smoke suite, where an env var can be set.
 */
class WorkspaceOptionTest {

    @Command(name = "host")
    static final class Host {
        @Mixin
        WorkspaceOption workspace;
    }

    private static WorkspaceOption parse(String... args) {
        Host host = new Host();
        new CommandLine(host).parseArgs(args);
        return host.workspace;
    }

    @Test
    void defaultsToCynWorkWhenNoFlagIsGiven() {
        assertThat(parse().root()).isEqualTo(Path.of("cyn-work"));
    }

    @Test
    void theFlagOverridesTheDefault() {
        assertThat(parse("-w", "/tmp/ws").root()).isEqualTo(Path.of("/tmp/ws"));
        assertThat(parse("--workdir", "ws2").root()).isEqualTo(Path.of("ws2"));
    }
}
