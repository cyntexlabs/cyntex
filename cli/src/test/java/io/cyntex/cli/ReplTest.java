package io.cyntex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline REPL's line dispatch: builtins (help / exit / quit), blank-line tolerance, quote-aware
 * tokenization, and routing every other line through the same verb table the one-shot mode uses.
 * The JLine read loop itself is not unit-tested; {@link Repl#dispatch} is the testable seam.
 */
class ReplTest {

    private record Harness(Repl repl, StringWriter sink) {
    }

    private static Harness harness() {
        return harness(Path.of("cyn-work"));
    }

    private static Harness harness(Path workdir) {
        CommandLine cl = Cli.newCommandLine();
        StringWriter sink = new StringWriter();
        PrintWriter pw = new PrintWriter(sink);
        cl.setOut(pw);
        cl.setErr(pw);
        return new Harness(new Repl(cl, workdir), sink);
    }

    /** Copies a classpath workspace tree into {@code dest}, preserving the kind-directory layout. */
    private static void copyWorkspace(String resource, Path dest) throws Exception {
        Path src = Path.of(ReplTest.class.getResource(resource).toURI());
        try (var files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path target = dest.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(f, target);
                }
            }
        }
    }

    @Test
    void exitStopsTheLoop() {
        assertThat(harness().repl().dispatch("exit")).isFalse();
    }

    @Test
    void quitStopsTheLoop() {
        assertThat(harness().repl().dispatch("quit")).isFalse();
    }

    @Test
    void blankLineContinuesWithoutOutput() {
        Harness h = harness();
        assertThat(h.repl().dispatch("   ")).isTrue();
        assertThat(h.sink().toString()).isEmpty();
    }

    @Test
    void helpPrintsUsageAndContinues() {
        Harness h = harness();
        assertThat(h.repl().dispatch("help")).isTrue();
        assertThat(h.sink().toString()).contains("validate");
    }

    @Test
    void verbsDispatchThroughTheSameTable() {
        Harness h = harness();
        assertThat(h.repl().dispatch("explain")).isTrue();
        assertThat(h.sink().toString()).contains("cyntex/v1");
    }

    @Test
    void structuredOutputFlagIsReachableThroughTheRepl() {
        Harness h = harness();
        // the -o flag and its lower-case enum value travel through the REPL's tokeniser and the
        // shared command table (which enables case-insensitive enum matching)
        assertThat(h.repl().dispatch("explain -o json")).isTrue();
        assertThat(h.sink().toString()).contains("\"path\"").contains("source");
    }

    @Test
    void tokenizeSplitsOnWhitespace() {
        assertThat(Repl.tokenize("  validate   /a/b  ")).containsExactly("validate", "/a/b");
    }

    @Test
    void tokenizeKeepsDoubleQuotedSpacesAsOneWord() {
        assertThat(Repl.tokenize("validate \"my workspace\"")).containsExactly("validate", "my workspace");
    }

    @Test
    void tokenizeKeepsSingleQuotedSpacesAsOneWord() {
        assertThat(Repl.tokenize("a 'b c' d")).containsExactly("a", "b c", "d");
    }

    @Test
    void dispatchHandlesAQuotedPathWithSpacesLikeTheOneShotForm(@TempDir Path base) throws Exception {
        Path spaced = Files.createDirectory(base.resolve("my workspace"));
        copyWorkspace("/ws-valid", spaced);
        Harness h = harness();
        boolean cont = h.repl().dispatch("validate \"" + spaced + "\"");
        assertThat(cont).isTrue();
        assertThat(h.sink().toString()).startsWith("valid:").contains("3 resources");
    }

    @Test
    void tokenizeReturnsEmptyForBlank() {
        assertThat(Repl.tokenize("   ")).isEqualTo(List.of());
    }

    // --- F1d: session-state workspace, -w injection, cd / pwd, prompt -----------------------------

    @Test
    void bareValidateUsesTheSessionWorkspaceRootNotTheProcessDefault() throws Exception {
        // a bare `validate` carries no path and no -w: the seeded session workspace must drive it, so
        // the loader sees ws-valid (the session root), not the process-relative cyn-work default
        Path wsRoot = Path.of(ReplTest.class.getResource("/ws-valid").toURI());
        Harness h = harness(wsRoot);
        assertThat(h.repl().dispatch("validate")).isTrue();
        assertThat(h.sink().toString())
                .startsWith("valid:").contains("3 resources").contains(wsRoot.toString());
    }

    @Test
    void anExplicitWorkdirFlagWinsOverTheInjectedSession() throws Exception {
        // the session root is bogus; an explicit -w on the line must win, so the run still succeeds —
        // proving the injection does not clobber a user-supplied workspace flag
        Path wsRoot = Path.of(ReplTest.class.getResource("/ws-valid").toURI());
        Harness h = harness(Path.of("/no/such/cyntex/session"));
        assertThat(h.repl().dispatch("validate -w " + wsRoot)).isTrue();
        assertThat(h.sink().toString()).startsWith("valid:").contains("3 resources");
    }

    @Test
    void aVerbWithoutTheWorkspaceOptionGetsNoInjectedWorkdir() {
        // explain declares no --workdir; the REPL must not inject one (it would be an unknown option)
        Harness h = harness(Path.of("cyn-work"));
        assertThat(h.repl().dispatch("explain")).isTrue();
        assertThat(h.sink().toString()).contains("cyntex/v1").doesNotContain("Unknown option");
    }

    @Test
    void cdChangesTheSessionWorkspaceToAnExistingSubdirectory(@TempDir Path base) throws Exception {
        Path sub = Files.createDirectory(base.resolve("staging"));
        Harness h = harness(base);
        assertThat(h.repl().dispatch("cd staging")).isTrue();
        assertThat(h.repl().workdir()).isEqualTo(sub);
    }

    @Test
    void cdToTheParentDirectoryResolvesAndNormalizes(@TempDir Path base) throws Exception {
        // `..` must be collapsed (normalize), not left as level1/.. — Path.equals is lexical, so an
        // un-normalized parent would not equal base and would leak into pwd / prompt / the injected -w
        Path sub = Files.createDirectory(base.resolve("level1"));
        Harness h = harness(sub);
        assertThat(h.repl().dispatch("cd ..")).isTrue();
        assertThat(h.repl().workdir()).isEqualTo(base);
    }

    @Test
    void cdToAMissingDirectoryReportsAnErrorAndKeepsTheWorkspace(@TempDir Path base) {
        Harness h = harness(base);
        assertThat(h.repl().dispatch("cd nope")).isTrue();
        assertThat(h.sink().toString()).contains("cd:").contains("nope");
        assertThat(h.repl().workdir()).isEqualTo(base);
    }

    @Test
    void cdWithNoArgumentReportsMissingOperand(@TempDir Path base) {
        Harness h = harness(base);
        assertThat(h.repl().dispatch("cd")).isTrue();
        assertThat(h.sink().toString()).contains("missing operand");
        assertThat(h.repl().workdir()).isEqualTo(base);
    }

    @Test
    void pwdPrintsTheCurrentWorkspace(@TempDir Path base) {
        Harness h = harness(base);
        assertThat(h.repl().dispatch("pwd")).isTrue();
        assertThat(h.sink().toString()).contains(base.toString());
    }

    @Test
    void promptShowsTheWorkspaceName() {
        assertThat(harness(Path.of("cyn-work")).repl().prompt()).isEqualTo("cyntex(offline:cyn-work)> ");
        assertThat(harness(Path.of("/tmp/projects/demo")).repl().prompt()).isEqualTo("cyntex(offline:demo)> ");
    }

    @Test
    void promptForARootWorkspaceFallsBackToTheFullPath() {
        // a filesystem root has no file name; the prompt must fall back to the path string, not NPE
        assertThat(harness(Path.of("/")).repl().prompt()).isEqualTo("cyntex(offline:/)> ");
    }

    @Test
    void cdUpdatesThePromptAndTheInjectedWorkspace(@TempDir Path base) throws Exception {
        // lay a valid workspace inside a subdirectory; after cd, the prompt names it and a bare
        // validate targets it — cd and -w injection working together end to end
        Path sub = Files.createDirectory(base.resolve("ws"));
        copyWorkspace("/ws-valid", sub);
        Harness h = harness(base);
        h.repl().dispatch("cd ws");
        assertThat(h.repl().prompt()).isEqualTo("cyntex(offline:ws)> ");
        assertThat(h.repl().dispatch("validate")).isTrue();
        assertThat(h.sink().toString()).contains("valid:").contains("3 resources");
    }

    @Test
    void cdMakesLsListTheNewWorkspaceResources(@TempDir Path base) throws Exception {
        // cd must re-point ls at the session workspace: ls over the empty base lists nothing, but after
        // cd into a populated workspace it lists that workspace's resources — the output follows the cd
        Path sub = Files.createDirectory(base.resolve("ws"));
        copyWorkspace("/ws-valid", sub);
        Harness h = harness(base);

        h.repl().dispatch("ls");
        int afterEmptyLs = h.sink().toString().length();
        assertThat(h.sink().toString()).doesNotContain("kfk2my");   // base has no kind dirs

        h.repl().dispatch("cd ws");
        h.repl().dispatch("ls");
        String afterCd = h.sink().toString().substring(afterEmptyLs);
        assertThat(afterCd).contains("source").contains("pipeline").contains("kfk2my");
    }

    @Test
    void cdMakesDescResolveAResourceFromTheNewWorkspace(@TempDir Path base) throws Exception {
        // desc resolves an id against the session workspace: before cd the id is not found in the empty
        // base; after cd into the populated workspace the same id describes its resource
        Path sub = Files.createDirectory(base.resolve("ws"));
        copyWorkspace("/ws-valid", sub);
        Harness h = harness(base);

        h.repl().dispatch("desc kfk2my");
        int afterMiss = h.sink().toString().length();
        assertThat(h.sink().toString()).contains("cli.resource-not-found");   // absent from base

        h.repl().dispatch("cd ws");
        h.repl().dispatch("desc kfk2my");
        String afterCd = h.sink().toString().substring(afterMiss);
        assertThat(afterCd).contains("kfk2my").contains("pipeline").doesNotContain("resource-not-found");
    }
}
