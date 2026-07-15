package io.cyntex.cli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dual-mode CLI's command table: the offline-verb whitelist (validate / new / explain), the coded
 * not-connected and not-implemented affordances (which must survive the operands these verbs are really
 * typed with), the exit-code contract, and validate wired to the offline DSL link.
 */
class CliTest {

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

    private static Path resource(String name) {
        try {
            return Path.of(CliTest.class.getResource("/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    static Stream<String> connectedVerbs() {
        return Cli.CONNECTED_VERBS.stream();
    }

    static Stream<String> unimplementedCompositeVerbs() {
        return Cli.UNIMPLEMENTED_COMPOSITE_VERBS.stream();
    }

    @Test
    void offlineVerbsAreRegistered() {
        assertThat(Cli.newCommandLine().getSubcommands().keySet())
                .contains("validate", "new", "explain");
    }

    @Test
    void connectedVerbsAreRegisteredNotMissing() {
        // connect is a REPL builtin (session-scoped), not a one-shot subcommand
        assertThat(Cli.newCommandLine().getSubcommands().keySet())
                .contains("apply", "run")
                .doesNotContain("connect");
    }

    @Test
    void offlineVerbWhitelistMatchesRegisteredSubcommands() {
        // single source of truth: every registered subcommand that needs neither a server nor an
        // implementation it has not got must be exactly the declared offline whitelist (so the
        // recovery hint can never drift)
        TreeSet<String> registeredOffline = new TreeSet<>(Cli.newCommandLine().getSubcommands().keySet());
        registeredOffline.removeAll(Cli.CONNECTED_VERBS);
        registeredOffline.removeAll(Cli.UNIMPLEMENTED_COMPOSITE_VERBS);
        assertThat(registeredOffline).containsExactlyInAnyOrderElementsOf(Cli.OFFLINE_VERBS);
    }

    @Test
    void validateAcceptsAValidWorkspace() {
        Run r = run("validate", resource("ws-valid").toString());
        assertThat(r.code()).isZero();
        // "invalid" contains "valid", so anchor on the success shape, not a bare substring
        assertThat(r.out()).startsWith("valid:").contains("3 resources");
        assertThat(r.err()).isEmpty();
    }

    @Test
    void validateJudgesAWorkspaceWithoutResolvingItsReferences(@TempDir Path ws) throws Exception {
        // validate is the offline verb: it reads no environment, so a reference stays opaque to it and a
        // workspace that carries one still validates. That is what lets a check run somewhere the
        // variables are not set — a build box, a reviewer's laptop — and it is the standing reason the
        // capability rules skip a value they cannot see. Resolution belongs to apply, which is the verb
        // that has an environment to resolve from.
        Files.createDirectory(ws.resolve("source"));
        Files.writeString(ws.resolve("source").resolve("src.cyn.yml"), """
                version: cyntex/v1
                kind: source
                id: src
                connector: mongodb
                config: { uri: "${MONGO_URI}" }
                """);

        Run r = run("validate", ws.toString());

        assertThat(r.code()).isZero();
        assertThat(r.out()).startsWith("valid:");
    }

    @Test
    void validateRejectsAnInvalidWorkspaceWithCodeAndLocation() {
        Run r = run("validate", resource("ws-invalid").toString());
        assertThat(r.code()).isEqualTo(1);
        // the dsl-domain error code surfaces, located at the offending file
        assertThat(r.all()).contains("dsl.unknown-field");
        assertThat(r.all()).contains("src_typo.cyn.yml");
    }

    @Test
    void validateHumanOutputRendersTheCatalogMessageNotJustTheCode() {
        Run r = run("validate", resource("ws-invalid").toString());
        assertThat(r.code()).isEqualTo(1);
        // the rendered, user-facing message — not only the bare dev string
        assertThat(r.all()).contains("Unknown field");
        // the canonical code stays visible as the stable, machine-referable identity
        assertThat(r.all()).contains("dsl.unknown-field");
    }

    @Test
    void validateJsonEmitsTheValidEnvelopeOnStdout() {
        Run r = run("validate", "-o", "json", resource("ws-valid").toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("\"status\": \"valid\"")
                .contains("\"resourceCount\": 3")
                .contains("\"diagnostics\": []");
    }

    @Test
    void validateJsonEmitsDiagnosticsOnStdoutWhenInvalid() {
        Run r = run("validate", "-o", "json", resource("ws-invalid").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("\"status\": \"invalid\"")
                .contains("\"code\": \"dsl.unknown-field\"")
                .contains("\"severity\": \"ERROR\"")
                .contains("\"source\": \"src_typo.cyn.yml\"")
                .contains("Unknown field");
    }

    @Test
    void validateYamlEmitsTheValidEnvelope() {
        Run r = run("validate", "-o", "yaml", resource("ws-valid").toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("status: valid")
                .contains("resourceCount: 3")
                .contains("diagnostics: []");
    }

    @Test
    void validateRejectsAnUnknownOutputFormat() {
        Run r = run("validate", "-o", "toml", resource("ws-valid").toString());
        assertThat(r.code()).isEqualTo(2);
    }

    @Test
    void validateMissingPathIsAUsageError() {
        Run r = run("validate", "/no/such/cyntex/workspace");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).containsIgnoringCase("not found");
    }

    @Test
    void validateEmptyWorkspaceIsAUsageErrorNotSilentSuccess(@TempDir Path empty) {
        Run r = run("validate", empty.toString());
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("no *.cyn.yml artifacts");
        assertThat(r.out()).doesNotContain("valid:");
    }

    @Test
    void validateRendersIoFaultCleanlyInsteadOfStackTrace(@TempDir Path dir) throws Exception {
        Path artifact = dir.resolve("locked.cyn.yml");
        Files.writeString(artifact, "version: cyntex/v1\nkind: source\nid: x\nconnector: mysql\nconfig: {}\n");
        boolean blocked = artifact.toFile().setReadable(false);
        Assumptions.assumeTrue(blocked && !Files.isReadable(artifact),
                "filesystem does not enforce owner-unreadable; skipping IO-fault rendering test");
        Run r = run("validate", dir.toString());
        assertThat(r.code()).isNotZero();
        assertThat(r.err()).contains("cannot read workspace");
        assertThat(r.err()).doesNotContain("Exception");   // a clean diagnostic, not a raw stack
    }

    @Test
    void validateRendersMalformedYamlAsACodedDiagnosticNotARawStack(@TempDir Path dir) throws Exception {
        // a syntactically broken file must surface as a coded dsl.malformed-yaml, never a raw snakeyaml stack
        Path artifact = dir.resolve("broken.cyn.yml");
        Files.writeString(artifact, "[unterminated\n");
        Run r = run("validate", artifact.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("dsl.malformed-yaml");
        assertThat(r.all()).doesNotContain("org.yaml.snakeyaml");
    }

    @Test
    void validateWithNoPathValidatesTheWorkspaceRoot() {
        // no positional path given: validate resolves to the workspace root carried by -w, not "."
        // ws-valid is laid out by kind (source/, pipeline/), so this also exercises the happy path of
        // the workspace-root layout gate — correctly placed artifacts pass enforcement
        String wsRoot = resource("ws-valid").toString();
        Run r = run("validate", "-w", wsRoot);
        assertThat(r.code()).isZero();
        // "invalid" contains "valid", so anchor on the success shape, not a bare substring
        assertThat(r.out()).startsWith("valid:").contains("3 resources");
        // the resolved workspace root is what gets echoed back, proving the root drove the run
        assertThat(r.out()).contains(wsRoot);
        assertThat(r.err()).isEmpty();
    }

    @Test
    void validateExplicitPathOverridesTheWorkspaceRoot() {
        // an explicit positional wins over -w: the bogus workspace root is ignored, the path validated
        String explicit = resource("ws-valid").toString();
        Run r = run("validate", "-w", "/no/such/cyntex/workspace", explicit);
        assertThat(r.code()).isZero();
        assertThat(r.out()).startsWith("valid:");
        // the explicit positional — not the bogus -w root — is the one resolved and echoed
        assertThat(r.out()).contains(explicit).doesNotContain("/no/such/cyntex/workspace");
    }

    @Test
    void validateNoPathReportsTheMissingWorkspaceRoot() {
        // with no positional and a workspace root that does not exist, the missing root is the diagnostic
        Run r = run("validate", "-w", "/no/such/cyntex/workspace");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("/no/such/cyntex/workspace");
        assertThat(r.err()).containsIgnoringCase("not found");
    }

    @Test
    void validateNoPathEmptyWorkspaceRootIsAUsageError(@TempDir Path empty) {
        // an existing but empty workspace root (resolved via -w, no positional) is a usage error,
        // and the diagnostic names the resolved root — exercising the empty-workspace branch through
        // the workspace mechanism, not just an explicit positional
        Run r = run("validate", "-w", empty.toString());
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("no *.cyn.yml artifacts").contains(empty.toString());
        assertThat(r.out()).doesNotContain("valid:");
    }

    @Test
    void validateWorkspaceRootRejectsArtifactInTheWrongKindDirectory() {
        // structure is truth, enforced on the managed workspace root (no positional, -w points at it):
        // a source file dropped into the pipeline/ directory is misplaced — a coded cli diagnostic
        // naming the offending file, not a silent pass
        Run r = run("validate", "-w", resource("ws-misplaced").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.all()).contains("cli.kind-dir-mismatch");
        assertThat(r.all()).contains("src_misplaced.cyn.yml");
        // the rendered, user-facing message — not only the bare code — naming the declared kind
        assertThat(r.all()).contains("declares kind 'source'");
    }

    @Test
    void validateWorkspaceRootRejectsASourceFileInTheViewDirectory() {
        // the canonical structure-is-truth case: a kind:source artifact placed under view/ is
        // misplaced. The gate is kind-generic, but this pins the exact view/ scenario — the coded
        // diagnostic names the offending file, its declared kind, and the directory it sits in.
        Run r = run("validate", "-w", resource("ws-view-misplaced").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.all()).contains("cli.kind-dir-mismatch");
        assertThat(r.all()).contains("src_in_view.cyn.yml");
        assertThat(r.all()).contains("declares kind 'source'");
        // the directory naming half of the message — proving the gate read view/, not some other dir
        // (the quoted phrase pins the {dir} param, so the src_in_view filename cannot satisfy it)
        assertThat(r.all()).contains("'view' directory");
    }

    @Test
    void validateWorkspaceRootRejectsArtifactSittingDirectlyAtTheRoot() {
        // a file directly at the workspace root (not under any kind directory) is misplaced too: its
        // parent is the root directory name, which never equals a kind
        Run r = run("validate", "-w", resource("ws-root-misplaced").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.all()).contains("cli.kind-dir-mismatch");
        assertThat(r.all()).contains("src_root.cyn.yml");
    }

    @Test
    void validateExplicitDirectorySkipsTheKindDirectoryCheck() {
        // an explicit positional directory is ad-hoc validation, not the managed workspace: the same
        // misplaced layout that fails via -w passes here, the gate does not fire
        Run r = run("validate", resource("ws-misplaced").toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).startsWith("valid:");
        assertThat(r.all()).doesNotContain("cli.kind-dir-mismatch");
    }

    @Test
    void validateSingleFileSkipsTheKindDirectoryCheck() {
        // a single named file carries no workspace-layout claim: the same misplaced source validates
        // fine when pointed at directly, even though it sits in the wrong kind directory
        Run r = run("validate",
                resource("ws-misplaced").resolve("pipeline").resolve("src_misplaced.cyn.yml").toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).startsWith("valid:");
        assertThat(r.all()).doesNotContain("cli.kind-dir-mismatch");
    }

    @Test
    void validateFlatDirectoryFromNewOutIsAccepted(@TempDir Path flat) {
        // the --out flat escape hatch must round-trip: scaffold a source flat, then validate that
        // directory explicitly — the layout gate does not fire on an explicit path, so it is accepted
        Run created = run("new", "--kind", "source", "--id", "src_flat", "--connector", "mysql",
                "--set", "host=10.0.0.1", "--set", "username=u", "--set", "password=p",
                "--out", flat.toString());
        assertThat(created.code()).isZero();
        Run r = run("validate", flat.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).startsWith("valid:");
        assertThat(r.all()).doesNotContain("cli.kind-dir-mismatch");
    }

    @Test
    void validateJsonEmitsTheKindDirMismatchDiagnostic() {
        Run r = run("validate", "-o", "json", "-w", resource("ws-misplaced").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("\"status\": \"invalid\"")
                .contains("\"code\": \"cli.kind-dir-mismatch\"")
                .contains("\"severity\": \"ERROR\"")
                .contains("\"source\": \"src_misplaced.cyn.yml\"")
                // all three declared params travel in the structured contract (TreeMap-sorted)
                .contains("\"dir\": \"pipeline\"")
                .contains("\"kind\": \"source\"")
                .contains("\"path\": \"src_misplaced.cyn.yml\"");
    }

    @Test
    void validateYamlEmitsTheKindDirMismatchDiagnostic() {
        Run r = run("validate", "-o", "yaml", "-w", resource("ws-misplaced").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("status: invalid")
                .contains("code: cli.kind-dir-mismatch")
                .contains("source: src_misplaced.cyn.yml")
                .contains("dir: pipeline")
                .contains("kind: source");
    }

    @Test
    void connectedVerbReportsNotConnectedRatherThanMissing() {
        Run r = run("apply");
        assertThat(r.code()).isEqualTo(3);
        assertThat(r.err()).contains("cli.not-connected");
    }

    @ParameterizedTest
    @MethodSource("connectedVerbs")
    void everyConnectedVerbReportsItsOwnName(String verb) {
        Run r = run(verb);
        assertThat(r.code()).isEqualTo(3);
        // the shared handler must render a coded diagnostic naming the verb actually typed, for all of
        // them — not just apply, and not a bare string that no catalog or machine reader can resolve
        assertThat(r.err()).contains("cli.not-connected").contains(verb);
    }

    @ParameterizedTest
    @MethodSource("connectedVerbs")
    void aConnectedVerbReportsNotConnectedEvenWhenGivenArguments(String verb) {
        // the affordance has to survive the way these verbs are actually typed — `apply x.yml`, not a
        // bare `apply`. A verb that takes no arguments would reject the operand as unmatched and print
        // usage instead, telling the user nothing about the connection they are missing.
        Run r = run(verb, "some-id", "--force");
        assertThat(r.code()).isEqualTo(3);
        assertThat(r.err()).contains("cli.not-connected").contains(verb);
    }

    @ParameterizedTest
    @MethodSource("unimplementedCompositeVerbs")
    void anUnimplementedCompositeVerbSaysSoRatherThanBlamingTheConnection(String verb) {
        Run r = run(verb);
        assertThat(r.code()).isEqualTo(3);
        // these verbs compose registered operations but have no implementation yet, so "you are not
        // connected" is simply false — connecting would not make them work
        assertThat(r.err()).contains("cli.verb-not-implemented").contains(verb);
        assertThat(r.err()).doesNotContain("cli.not-connected");
    }

    @ParameterizedTest
    @MethodSource("unimplementedCompositeVerbs")
    void anUnimplementedCompositeVerbSaysSoEvenWhenGivenArguments(String verb) {
        Run r = run(verb, "some-id", "--force");
        assertThat(r.code()).isEqualTo(3);
        assertThat(r.err()).contains("cli.verb-not-implemented").contains(verb);
    }

    @Test
    void unknownVerbIsAUsageErrorDistinctFromConnectedVerbs() {
        Run r = run("florp");
        // a usage error (exit 2), naming the offending token — not the connected-verb code (3)
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("florp");
    }

    @Test
    void explainRootListsTheResourceKinds() {
        Run r = run("explain");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("cyntex/v1");
        assertThat(r.out()).contains("source").contains("pipeline").contains("serve");
    }

    @Test
    void explainScalarFieldRendersTypeAndDescription() {
        Run r = run("explain", "source.connector");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("source.connector");
        assertThat(r.out()).contains("string");
        // the field's own description from the schema
        assertThat(r.out()).contains("connector");
        // a required field is marked as such
        assertThat(r.out()).containsIgnoringCase("required");
    }

    @Test
    void explainEnumFieldListsItsAllowedValues() {
        Run r = run("explain", "source.mode");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("source.mode").contains("enum");
        assertThat(r.out()).contains("cdc").contains("snapshot").contains("stream").contains("api");
        // each value renders with its schema description, not just the bare token
        assertThat(r.out()).contains("Change data capture");
    }

    @Test
    void explainObjectFieldListsItsChildFieldsWithTypeAndRequiredMarker() {
        Run r = run("explain", "source");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("connector").contains("mode").contains("tables");
        // the FIELDS table carries each child's type and marks the required ones
        assertThat(r.out()).contains("enum").contains("string").containsIgnoringCase("required");
    }

    @Test
    void explainUnknownPathIsAUsageError() {
        Run r = run("explain", "source.bogus");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("source.bogus");
        assertThat(r.out()).doesNotContain("source.bogus");
    }

    @Test
    void explainJsonEmitsTheFieldNodeEnvelope() {
        Run r = run("explain", "-o", "json", "source.mode");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("\"path\": \"source.mode\"")
                .contains("\"type\": \"enum\"")
                .contains("\"values\"")
                .contains("cdc");
        // the envelope carries description and required as part of the machine contract
        assertThat(r.out()).contains("\"required\": false").contains("\"description\":");
    }

    @Test
    void explainJsonFieldsCarryEachChildTypeAndRequiredFlag() {
        Run r = run("explain", "-o", "json", "source");
        assertThat(r.code()).isZero();
        // each fields[] entry summarises the child: name + type + required
        assertThat(r.out()).contains("\"name\": \"mode\"").contains("\"type\": \"enum\"");
        assertThat(r.out()).contains("\"name\": \"connector\"").contains("\"required\": true");
    }

    @Test
    void explainJsonRootEmitsTheResourceKinds() {
        Run r = run("explain", "-o", "json");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("\"path\": \"\"")
                .contains("\"fields\"")
                .contains("\"name\": \"source\"")
                .contains("\"type\": \"object\"");
    }

    @Test
    void explainYamlEmitsTheFieldNodeEnvelope() {
        Run r = run("explain", "-o", "yaml", "source");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("path: source").contains("type: object").contains("fields:");
        // description and required travel in the yaml envelope too
        assertThat(r.out()).contains("required: false").contains("description:");
    }

    @Test
    void explainUnknownPathInJsonModeIsStillAPlainUsageError() {
        // a non-existent path is a CLI usage affordance, not a coded domain diagnostic — even with -o
        Run r = run("explain", "-o", "json", "bogus");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("bogus");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void versionFlagPrintsTheVersion() {
        Run r = run("--version");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("cyntex 0.1.0");
    }

    @Test
    void outputFormatCompletionCandidatesAreTheLowercaseSpelling() {
        // the -o values complete and display as text/json/yaml (the documented spelling), not the
        // upper-case enum constant names; parsing stays case-insensitive
        for (String verb : List.of("validate", "explain")) {
            var option = Cli.newCommandLine().getSubcommands().get(verb)
                    .getCommandSpec().findOption("-o");
            assertThat(option.completionCandidates()).containsExactly("text", "json", "yaml");
        }
    }

    @Test
    void newWithNoAnswersIsAUsageErrorNotAnUnknownCommand() {
        // `new` is a real, routed verb: invoked bare with no answers (and no terminal to prompt at)
        // it explains how to use itself — a usage error (exit 2) carrying its own guidance, not the
        // unmatched-command rejection `florp` gets.
        Run r = run("new");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("--connector");
    }

    // --- F1d: REPL-launch detection + workspace seed ---------------------------------------------

    @Test
    void bareArgsLaunchTheReplWithTheDefaultWorkspace() {
        assertThat(Cli.isReplLaunch(new String[]{})).isTrue();
        assertThat(WorkspaceOption.resolve()).isEqualTo(Path.of("cyn-work"));
    }

    @Test
    void workspaceOnlyArgsLaunchTheReplSeededWithThatWorkspace() {
        assertThat(Cli.isReplLaunch(new String[]{"-w", "foo"})).isTrue();
        assertThat(Cli.isReplLaunch(new String[]{"--workdir", "foo"})).isTrue();
        assertThat(Cli.isReplLaunch(new String[]{"--workdir=foo"})).isTrue();
        // the seed honours the flag with the option's own precedence
        assertThat(WorkspaceOption.resolve("-w", "foo")).isEqualTo(Path.of("foo"));
        assertThat(WorkspaceOption.resolve("--workdir=bar")).isEqualTo(Path.of("bar"));
    }

    @Test
    void aVerbIsOneShotNotAReplLaunch() {
        assertThat(Cli.isReplLaunch(new String[]{"validate"})).isFalse();
        assertThat(Cli.isReplLaunch(new String[]{"new", "--kind", "source"})).isFalse();
        // a verb after a workspace flag is still one-shot: the top-level table has no -w, so this is a
        // loud usage error rather than a silently-ignored workspace
        assertThat(Cli.isReplLaunch(new String[]{"-w", "foo", "validate"})).isFalse();
    }

    @Test
    void helpVersionAndUnknownTokensAreOneShot() {
        assertThat(Cli.isReplLaunch(new String[]{"--help"})).isFalse();
        assertThat(Cli.isReplLaunch(new String[]{"--version"})).isFalse();
        assertThat(Cli.isReplLaunch(new String[]{"florp"})).isFalse();
    }

    @Test
    void resolveThrowsOnAMalformedWorkspaceFlag() {
        // a -w with no value is malformed; resolve must throw so the REPL-launch path in main can fall
        // back to the command table for a loud usage error instead of seeding the REPL with junk
        assertThatThrownBy(() -> WorkspaceOption.resolve("-w")).isInstanceOf(RuntimeException.class);
    }
}
