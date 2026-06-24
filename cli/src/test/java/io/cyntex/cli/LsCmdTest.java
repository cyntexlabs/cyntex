package io.cyntex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ls} — the workspace browser. Reads the workspace structurally (each {@code <root>/<kind>/}
 * directory is that kind's truth), listing {@code id} plus a per-kind summary. This suite drives the
 * one-shot surface and the REPL session-workspace wiring, asserting grouped / single-kind listings,
 * the source and pipeline summaries, the {@code -o json|yaml} envelope, the friendly empty case, the
 * unknown-kind usage error, and that an unreadable artifact is surfaced rather than silently dropped.
 */
class LsCmdTest {

    /** Captured outcome of one one-shot CLI invocation. */
    private record Run(int code, String out, String err) {
        String all() {
            return out + err;
        }
    }

    private static Run run(String... args) {
        CommandLine cl = Cli.newCommandLine();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cl.setOut(new PrintWriter(out));
        cl.setErr(new PrintWriter(err));
        int code = cl.execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    /** The number of non-overlapping occurrences of {@code needle} in {@code haystack}. */
    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /** Scaffolds a sample workspace into {@code ws}: two sources (one with a mode) and one pipeline. */
    private static void scaffold(Path ws) {
        assertThat(run("new", "--non-interactive", "--kind", "source",
                "--connector", "mysql", "--id", "src_a", "--mode", "cdc", "-w", ws.toString()).code()).isZero();
        assertThat(run("new", "--non-interactive", "--kind", "source",
                "--connector", "postgres", "--id", "src_b", "-w", ws.toString()).code()).isZero();
        assertThat(run("new", "--non-interactive", "--kind", "pipeline",
                "--id", "p1", "--source", "src_a", "--source", "src_b", "--sync-to", "src_b",
                "-w", ws.toString()).code()).isZero();
    }

    @Test
    void lsListsResourcesGroupedByKind(@TempDir Path ws) {
        scaffold(ws);
        Run r = run("ls", "-w", ws.toString());
        assertThat(r.code()).isZero();
        // a header per non-empty kind with its count, and every id under it
        assertThat(r.out()).contains("source (2)").contains("pipeline (1)");
        assertThat(r.out()).contains("src_a").contains("src_b").contains("p1");
        // the source group is listed before the pipeline group (KINDS display order)
        assertThat(r.out().indexOf("src_a")).isLessThan(r.out().indexOf("p1"));
        assertThat(r.err()).isEmpty();
    }

    @Test
    void lsSingleKindShowsOnlyThatKind(@TempDir Path ws) {
        scaffold(ws);
        Run r = run("ls", "source", "-w", ws.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("src_a").contains("src_b");
        // the pipeline kind is filtered out entirely
        assertThat(r.out()).doesNotContain("p1").doesNotContain("pipeline (");
    }

    @Test
    void lsSourceSummaryShowsConnectorAndMode(@TempDir Path ws) {
        scaffold(ws);
        Run r = run("ls", "source", "-w", ws.toString());
        assertThat(r.code()).isZero();
        // src_a carries connector + mode; src_b (no mode) shows the connector ALONE — pin the no-mode
        // shape so inverting the mode ternary (always append a mode) cannot pass: no trailing separator
        // after the connector, and no leaked "null"
        assertThat(r.out()).contains("mysql").contains("cdc");
        assertThat(r.out()).contains("postgres").doesNotContain("postgres,").doesNotContain("null");
    }

    @Test
    void lsPipelineSummaryShowsSourceCountAndSurfaces(@TempDir Path ws) throws Exception {
        // a pipeline carrying both a view and a serve, written straight into its kind directory
        Files.createDirectories(ws.resolve("pipeline"));
        Files.writeString(ws.resolve("pipeline").resolve("p_full.cyn.yml"),
                """
                version: cyntex/v1
                kind: pipeline
                id: p_full
                source: src_a
                view:
                  id: v1
                  from: /.*/
                  primary_key: cust_id
                serve:
                  id: serve
                  from: v1
                  sync:
                    - id: sync_1
                      source: src_b
                """);
        Run r = run("ls", "pipeline", "-w", ws.toString());
        assertThat(r.code()).isZero();
        // one source, and both surfaces present
        assertThat(r.out()).contains("p_full");
        assertThat(r.out()).contains("1 source").contains("view").contains("serve");
    }

    @Test
    void lsJsonEmitsAResourceArrayEnvelopeOnStdout(@TempDir Path ws) {
        scaffold(ws);
        Run r = run("ls", "-o", "json", "-w", ws.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("\"workspace\":").contains("\"resources\":");
        assertThat(r.out()).contains("\"kind\": \"source\"")
                .contains("\"id\": \"src_a\"")
                .contains("\"connector\": \"mysql\"")
                .contains("\"mode\": \"cdc\"");
        // the no-mode source carries its connector but NO mode key — exactly one "mode" in the whole
        // envelope (src_a's), so the omit-when-null branch cannot be replaced by an always-emit
        assertThat(r.out()).contains("\"id\": \"src_b\"").contains("\"connector\": \"postgres\"");
        assertThat(countOf(r.out(), "\"mode\"")).isEqualTo(1);
        // the pipeline entry carries a numeric source count and BOTH boolean surfaces (view is false)
        assertThat(r.out()).contains("\"kind\": \"pipeline\"")
                .contains("\"sources\": 2")
                .contains("\"view\": false")
                .contains("\"serve\": true");
        // good entries carry no readable key — only an unreadable entry does (no broken file here)
        assertThat(r.out()).doesNotContain("readable");
    }

    @Test
    void lsYamlEmitsAResourceArrayEnvelope(@TempDir Path ws) {
        scaffold(ws);
        Run r = run("ls", "-o", "yaml", "-w", ws.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("resources:")
                .contains("kind: source")
                .contains("id: src_a")
                .contains("connector: mysql")
                .contains("mode: cdc");
        assertThat(r.out()).contains("kind: pipeline").contains("sources: 2")
                .contains("view: false").contains("serve: true");
    }

    @Test
    void lsEmptyWorkspaceListsNothingNotAnError(@TempDir Path empty) {
        Run r = run("ls", "-w", empty.toString());
        // an empty (or not-yet-created) workspace is a normal browse outcome, not a usage error
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("no resources");
        assertThat(r.err()).isEmpty();
    }

    @Test
    void lsEmptyWorkspaceJsonEmitsAnEmptyResourceArray(@TempDir Path empty) {
        Run r = run("ls", "-o", "json", "-w", empty.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("\"resources\": []");
    }

    @Test
    void lsUnknownKindIsAUsageError(@TempDir Path ws) {
        scaffold(ws);
        Run r = run("ls", "widget", "-w", ws.toString());
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("widget");
        assertThat(r.out()).doesNotContain("src_a");
    }

    @Test
    void lsUnreadableArtifactIsSurfacedNotSilentlyDropped(@TempDir Path ws) throws Exception {
        // a malformed file in the source directory must be shown (zero-silent-drop), never hidden
        Files.createDirectories(ws.resolve("source"));
        Files.writeString(ws.resolve("source").resolve("broken.cyn.yml"), "[unterminated\n");
        Run r = run("ls", "source", "-w", ws.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("broken").containsIgnoringCase("unreadable");
    }

    @Test
    void lsUnreadableArtifactIsMarkedInTheJsonEnvelope(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("source"));
        Files.writeString(ws.resolve("source").resolve("broken.cyn.yml"), "[unterminated\n");
        Run r = run("ls", "-o", "json", "source", "-w", ws.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("\"id\": \"broken\"").contains("\"readable\": false");
    }

    @Test
    void lsMisplacedSourceInPipelineDirIsFlaggedNotMisrendered(@TempDir Path ws) throws Exception {
        // a well-formed source document physically in the pipeline/ directory: the json discriminator
        // is its structural slot (pipeline), but it must be flagged misplaced — never emitted as a
        // {kind: pipeline ... connector ...} self-contradiction a machine consumer would misread
        Files.createDirectories(ws.resolve("pipeline"));
        Files.writeString(ws.resolve("pipeline").resolve("stray.cyn.yml"),
                "version: cyntex/v1\nkind: source\nid: stray_src\nconnector: mysql\nmode: cdc\n");
        Run r = run("ls", "-o", "json", "pipeline", "-w", ws.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("\"kind\": \"pipeline\"")
                .contains("\"id\": \"stray_src\"")
                .contains("\"misplaced\": true")
                .contains("\"declaredKind\": \"source\"");
        // the discriminator and the body agree: no source-only fields leak under kind:pipeline
        assertThat(r.out()).doesNotContain("connector").doesNotContain("\"mode\"");
    }

    @Test
    void lsMisplacedPipelineInSourceDirIsFlaggedNotMisrendered(@TempDir Path ws) throws Exception {
        // the reverse direction: a pipeline document in source/ must not borrow source's connector/mode
        // shape — it is flagged misplaced, with no pipeline-only fields leaking under kind:source
        Files.createDirectories(ws.resolve("source"));
        Files.writeString(ws.resolve("source").resolve("stray.cyn.yml"),
                "version: cyntex/v1\nkind: pipeline\nid: stray_pipe\nsource: a\n"
                        + "serve:\n  id: serve\n  from: /.*/\n  sync:\n    - id: sync_1\n      source: a\n");
        Run r = run("ls", "-o", "json", "source", "-w", ws.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("\"kind\": \"source\"")
                .contains("\"id\": \"stray_pipe\"")
                .contains("\"misplaced\": true")
                .contains("\"declaredKind\": \"pipeline\"");
        assertThat(r.out()).doesNotContain("\"sources\"").doesNotContain("\"serve\"");
    }

    @Test
    void lsMisplacedArtifactIsFlaggedInTextToo(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("pipeline"));
        Files.writeString(ws.resolve("pipeline").resolve("stray.cyn.yml"),
                "version: cyntex/v1\nkind: source\nid: stray_src\nconnector: mysql\n");
        Run r = run("ls", "pipeline", "-w", ws.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("stray_src").containsIgnoringCase("misplaced").contains("source");
    }

    @Test
    void lsIsRegisteredAsAnOfflineVerb() {
        assertThat(Cli.OFFLINE_VERBS).contains("ls");
        assertThat(Cli.newCommandLine().getSubcommands().keySet()).contains("ls");
        // the whitelist and the registered offline subcommands stay in lockstep
        TreeSet<String> registeredOffline = new TreeSet<>(Cli.newCommandLine().getSubcommands().keySet());
        registeredOffline.removeAll(Cli.CONNECTED_VERBS);
        assertThat(registeredOffline).containsExactlyInAnyOrderElementsOf(Cli.OFFLINE_VERBS);
    }

    @Test
    void lsThroughTheReplUsesTheSessionWorkspace(@TempDir Path ws) {
        // dispatched bare through the REPL, ls inherits the session workspace via -w injection
        scaffold(ws);
        CommandLine cl = Cli.newCommandLine();
        StringWriter sink = new StringWriter();
        PrintWriter pw = new PrintWriter(sink);
        cl.setOut(pw);
        cl.setErr(pw);
        Repl repl = new Repl(cl, ws);
        assertThat(repl.dispatch("ls")).isTrue();
        assertThat(sink.toString()).contains("src_a").contains("p1");
    }
}
