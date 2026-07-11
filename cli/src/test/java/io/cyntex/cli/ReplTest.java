package io.cyntex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    private static Harness harness(Path workdir, ControlPlaneClient controlPlane) {
        CommandLine cl = Cli.newCommandLine();
        StringWriter sink = new StringWriter();
        PrintWriter pw = new PrintWriter(sink);
        cl.setOut(pw);
        cl.setErr(pw);
        return new Harness(new Repl(cl, workdir, controlPlane), sink);
    }

    private static Harness harness(Path workdir, ControlPlaneClient controlPlane, Prompter prompter) {
        CommandLine cl = Cli.newCommandLine();
        StringWriter sink = new StringWriter();
        PrintWriter pw = new PrintWriter(sink);
        cl.setOut(pw);
        cl.setErr(pw);
        return new Harness(new Repl(cl, workdir, controlPlane, prompter), sink);
    }

    /**
     * A network-free stand-in that answers healthy only for the given base URLs and records probes. The
     * connected verbs return their canned outcome when the target base is healthy and {@link
     * ApplyOutcome.Unreachable}-style unreachable when it is not — so a test can knock the landing node
     * down and exercise the failover-and-retry path with the same fake.
     */
    private static final class FakeControlPlane implements ControlPlaneClient {
        private final Set<URI> healthy;
        final List<URI> probed = new ArrayList<>();
        /** The canned login outcome and a log of the login calls made ({@code user:pass@base}). */
        LoginOutcome loginOutcome = new LoginOutcome.Unreachable();
        final List<String> loginCalls = new ArrayList<>();

        /** The canned connected-verb outcomes (used when the target base is healthy) and their call logs. */
        ApplyOutcome applyOutcome = new ApplyOutcome.Unreachable();
        GetOutcome getOutcome = new GetOutcome.Unreachable();
        ListOutcome listOutcome = new ListOutcome.Unreachable();
        LifecycleOutcome lifecycleOutcome = new LifecycleOutcome.Unreachable();
        final List<String> applyCalls = new ArrayList<>();
        final List<String> getCalls = new ArrayList<>();
        final List<String> listCalls = new ArrayList<>();
        final List<String> lifecycleCalls = new ArrayList<>();

        FakeControlPlane(URI... healthy) {
            this.healthy = new LinkedHashSet<>(List.of(healthy));
        }

        @Override
        public boolean isHealthy(URI baseUrl) {
            probed.add(baseUrl);
            return healthy.contains(baseUrl);
        }

        /** Replaces the reachable set, so a test can knock a landing node down mid-session. */
        void setHealthy(URI... urls) {
            healthy.clear();
            healthy.addAll(List.of(urls));
        }

        @Override
        public LoginOutcome login(URI baseUrl, String username, String password) {
            loginCalls.add(username + ":" + password + "@" + baseUrl);
            return loginOutcome;
        }

        @Override
        public ApplyOutcome apply(URI baseUrl, String credential, List<LocalDraft> drafts) {
            applyCalls.add(credential + "@" + baseUrl + " x" + drafts.size());
            return healthy.contains(baseUrl) ? applyOutcome : new ApplyOutcome.Unreachable();
        }

        @Override
        public GetOutcome get(URI baseUrl, String credential, String id) {
            getCalls.add(credential + "@" + baseUrl + "/" + id);
            return healthy.contains(baseUrl) ? getOutcome : new GetOutcome.Unreachable();
        }

        @Override
        public ListOutcome list(URI baseUrl, String credential, String kind) {
            listCalls.add(credential + "@" + baseUrl + "?" + kind);
            return healthy.contains(baseUrl) ? listOutcome : new ListOutcome.Unreachable();
        }

        @Override
        public LifecycleOutcome lifecycle(URI baseUrl, String credential, String pipelineId, String verb) {
            lifecycleCalls.add(credential + "@" + baseUrl + " " + verb + " " + pipelineId);
            return healthy.contains(baseUrl) ? lifecycleOutcome : new LifecycleOutcome.Unreachable();
        }
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

    // --- online-session skeleton: connect / disconnect / prompt / seed parsing --------------------

    @Test
    void connectToAReachableSeedFlipsTheSessionAndPrompt() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = harness(Path.of("cyn-work"), client);
        assertThat(h.repl().dispatch("connect node1:7900")).isTrue();
        assertThat(h.repl().session().isConnected()).isTrue();
        assertThat(h.repl().session().landingNode()).isEqualTo(URI.create("http://node1:7900"));
        assertThat(h.repl().prompt()).isEqualTo("cyntex(node1:7900)> ");
        assertThat(h.sink().toString()).contains("connected").contains("node1:7900");
    }

    @Test
    void connectTriesSeedsInOrderAndLandsOnTheFirstReachable() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node2:7900"));
        Harness h = harness(Path.of("cyn-work"), client);
        assertThat(h.repl().dispatch("connect node1:7900,node2:7900")).isTrue();
        assertThat(h.repl().session().landingNode()).isEqualTo(URI.create("http://node2:7900"));
        assertThat(client.probed)
                .containsExactly(URI.create("http://node1:7900"), URI.create("http://node2:7900"));
        assertThat(h.repl().prompt()).isEqualTo("cyntex(node2:7900)> ");
    }

    @Test
    void connectWithNoReachableSeedRendersConnectFailedAndStaysOffline() {
        FakeControlPlane client = new FakeControlPlane();   // nothing is healthy
        Harness h = harness(Path.of("cyn-work"), client);
        assertThat(h.repl().dispatch("connect node1:7900,node2:7900")).isTrue();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(h.repl().prompt()).isEqualTo("cyntex(offline:cyn-work)> ");
        assertThat(h.sink().toString())
                .contains("cli.connect-failed")
                .contains("http://node1:7900")
                .contains("http://node2:7900");
    }

    @Test
    void connectWithNoArgumentPrintsUsageAndLeavesTheSessionOffline() {
        FakeControlPlane client = new FakeControlPlane();
        Harness h = harness(Path.of("cyn-work"), client);
        assertThat(h.repl().dispatch("connect")).isTrue();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(h.sink().toString()).contains("connect:");
        assertThat(client.probed).isEmpty();
    }

    @Test
    void connectWithABlankSeedListPrintsUsageAndDoesNotProbe() {
        FakeControlPlane client = new FakeControlPlane();
        Harness h = harness(Path.of("cyn-work"), client);
        assertThat(h.repl().dispatch("connect \" , \"")).isTrue();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(h.sink().toString()).contains("connect:");
        assertThat(client.probed).isEmpty();
    }

    @Test
    void connectWithAUriIllegalSeedPrintsUsageStaysOfflineAndDoesNotProbe() {
        // `^` is illegal in a URI authority; the token must be treated as a usage-level input error,
        // not bubble an uncaught IllegalArgumentException that would crash the read loop
        FakeControlPlane client = new FakeControlPlane();
        Harness h = harness(Path.of("cyn-work"), client);
        assertThat(h.repl().dispatch("connect foo^bar")).isTrue();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(h.sink().toString()).contains("connect:").contains("foo^bar");
        assertThat(client.probed).isEmpty();
    }

    @Test
    void connectWithAPipeIllegalSeedPrintsUsageAndDoesNotProbe() {
        // `|` is likewise illegal in a URI; same total-parse requirement
        FakeControlPlane client = new FakeControlPlane();
        Harness h = harness(Path.of("cyn-work"), client);
        assertThat(h.repl().dispatch("connect a|b")).isTrue();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(h.sink().toString()).contains("connect:").contains("a|b");
        assertThat(client.probed).isEmpty();
    }

    @Test
    void connectWithAHostlessSeedPrintsUsageAndStaysOffline() {
        // `foo:bar` parses (a non-numeric port makes the authority registry-based) but has no host;
        // such a seed is unusable and must be rejected as a usage error, not probed
        FakeControlPlane client = new FakeControlPlane();
        Harness h = harness(Path.of("cyn-work"), client);
        assertThat(h.repl().dispatch("connect foo:bar")).isTrue();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(h.sink().toString()).contains("connect:").contains("foo:bar");
        assertThat(client.probed).isEmpty();
    }

    @Test
    void connectRejectsTheWholeLineOnABadSeedWithoutProbingTheGoodOne() {
        // the good seed is healthy, but a single invalid seed rejects the whole line before any probe,
        // so a typo can never silently connect to a subset
        FakeControlPlane client = new FakeControlPlane(URI.create("http://goodhost:80"));
        Harness h = harness(Path.of("cyn-work"), client);
        assertThat(h.repl().dispatch("connect goodhost:80,foo^bar")).isTrue();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(h.sink().toString()).contains("connect:").contains("foo^bar");
        assertThat(client.probed).isEmpty();
    }

    @Test
    void connectingLeavesTheSessionUnauthenticated() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = harness(Path.of("cyn-work"), client);
        h.repl().dispatch("connect node1:7900");
        assertThat(h.repl().session().isConnected()).isTrue();
        // connect establishes a transport target only; no credential is obtained until login
        assertThat(h.repl().session().isAuthenticated()).isFalse();
        assertThat(h.repl().session().credential()).isNull();
        assertThat(h.repl().session().principal()).isNull();
    }

    @Test
    void parseSeedsGivesABareHostPortAnHttpScheme() {
        Repl.ParsedSeeds parsed = Repl.parseSeeds("node1:7900");
        assertThat(parsed.valid()).containsExactly(URI.create("http://node1:7900"));
        assertThat(parsed.invalidToken()).isNull();
    }

    @Test
    void parseSeedsKeepsAnExplicitScheme() {
        assertThat(Repl.parseSeeds("http://host:8080").valid())
                .containsExactly(URI.create("http://host:8080"));
        assertThat(Repl.parseSeeds("https://secure:8443").valid())
                .containsExactly(URI.create("https://secure:8443"));
    }

    @Test
    void parseSeedsSplitsCommaSeparatedAndTrimsEach() {
        assertThat(Repl.parseSeeds(" node1:7900 , http://node2:8080 ").valid())
                .containsExactly(URI.create("http://node1:7900"), URI.create("http://node2:8080"));
    }

    @Test
    void parseSeedsDropsBlankElements() {
        assertThat(Repl.parseSeeds("node1:7900,,").valid()).containsExactly(URI.create("http://node1:7900"));
        Repl.ParsedSeeds allBlank = Repl.parseSeeds("  , ");
        assertThat(allBlank.valid()).isEmpty();
        assertThat(allBlank.invalidToken()).isNull();
    }

    @Test
    void parseSeedsReportsTheFirstUriIllegalTokenWithoutThrowing() {
        Repl.ParsedSeeds parsed = Repl.parseSeeds("node1:7900,foo^bar");
        assertThat(parsed.invalidToken()).isEqualTo("foo^bar");
        assertThat(parsed.valid()).isEmpty();
    }

    @Test
    void parseSeedsReportsAHostlessTokenAsInvalid() {
        Repl.ParsedSeeds parsed = Repl.parseSeeds("foo:bar");
        assertThat(parsed.invalidToken()).isEqualTo("foo:bar");
        assertThat(parsed.valid()).isEmpty();
    }

    @Test
    void disconnectReturnsAConnectedSessionToOffline() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = harness(Path.of("cyn-work"), client);
        h.repl().dispatch("connect node1:7900");
        assertThat(h.repl().dispatch("disconnect")).isTrue();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(h.repl().prompt()).isEqualTo("cyntex(offline:cyn-work)> ");
        assertThat(h.sink().toString()).contains("disconnected");
    }

    @Test
    void disconnectWhileOfflinePrintsABenignLine() {
        FakeControlPlane client = new FakeControlPlane();
        Harness h = harness(Path.of("cyn-work"), client);
        assertThat(h.repl().dispatch("disconnect")).isTrue();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(h.sink().toString()).contains("not connected");
    }

    // --- login / logout / authenticated prompt ---------------------------------------------------

    @Test
    void loginBeforeConnectReportsNotConnectedAndDoesNotCallTheClient() {
        FakeControlPlane client = new FakeControlPlane();
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("pw"));
        assertThat(h.repl().dispatch("login alice")).isTrue();
        assertThat(h.repl().session().isAuthenticated()).isFalse();
        // the not-connected state is a coded cli.* diagnostic naming the login verb, not a bare string
        assertThat(h.sink().toString()).contains("cli.not-connected").contains("login");
        assertThat(client.loginCalls).isEmpty();
    }

    @Test
    void loginWithNoUsernameReportsMissingOperandAndDoesNotCallTheClient() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("pw"));
        h.repl().dispatch("connect node1:7900");
        assertThat(h.repl().dispatch("login")).isTrue();
        assertThat(h.sink().toString()).contains("login:").contains("missing operand");
        assertThat(client.loginCalls).isEmpty();
    }

    @Test
    void loginReadsAMaskedPasswordAndAuthenticatesOnSuccess() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.loginOutcome = new LoginOutcome.Success("jwt-abc");
        ScriptedPrompter prompter = new ScriptedPrompter("s3cret");
        Harness h = harness(Path.of("cyn-work"), client, prompter);
        h.repl().dispatch("connect node1:7900");
        assertThat(h.repl().dispatch("login alice")).isTrue();
        assertThat(h.repl().session().isAuthenticated()).isTrue();
        assertThat(h.repl().session().principal()).isEqualTo("alice");
        assertThat(h.repl().session().credential()).isEqualTo("jwt-abc");
        assertThat(prompter.secretQuestions).isNotEmpty();   // the password was read masked, never echoed
        assertThat(client.loginCalls).containsExactly("alice:s3cret@http://node1:7900");
        assertThat(h.sink().toString()).contains("logged in as alice");
    }

    @Test
    void authenticatedPromptShowsThePrincipalAtTheNode() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.loginOutcome = new LoginOutcome.Success("jwt-abc");
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("s3cret"));
        h.repl().dispatch("connect node1:7900");
        assertThat(h.repl().prompt()).isEqualTo("cyntex(node1:7900)> ");   // connected, unauthenticated
        h.repl().dispatch("login alice");
        assertThat(h.repl().prompt()).isEqualTo("cyntex(alice@node1:7900)> ");
    }

    @Test
    void loginRejectedRendersTheServerErrorAndStaysUnauthenticated() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.loginOutcome = new LoginOutcome.Rejected("control.auth-failed", "Login failed.");
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("wrong"));
        h.repl().dispatch("connect node1:7900");
        assertThat(h.repl().dispatch("login alice")).isTrue();
        assertThat(h.repl().session().isAuthenticated()).isFalse();
        assertThat(h.sink().toString()).contains("control.auth-failed").contains("Login failed.");
        assertThat(h.repl().prompt()).isEqualTo("cyntex(node1:7900)> ");   // stays connected-unauthenticated
    }

    @Test
    void loginUnreachableReportsAndStaysUnauthenticated() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.loginOutcome = new LoginOutcome.Unreachable();
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("pw"));
        h.repl().dispatch("connect node1:7900");
        assertThat(h.repl().dispatch("login alice")).isTrue();
        assertThat(h.repl().session().isAuthenticated()).isFalse();
        assertThat(h.sink().toString()).contains("login:").contains("node1:7900");
    }

    @Test
    void logoutClearsAuthenticationButKeepsTheConnection() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.loginOutcome = new LoginOutcome.Success("jwt");
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("pw"));
        h.repl().dispatch("connect node1:7900");
        h.repl().dispatch("login alice");
        assertThat(h.repl().dispatch("logout")).isTrue();
        assertThat(h.repl().session().isAuthenticated()).isFalse();
        assertThat(h.repl().session().isConnected()).isTrue();
        assertThat(h.sink().toString()).contains("logged out");
        assertThat(h.repl().prompt()).isEqualTo("cyntex(node1:7900)> ");
    }

    @Test
    void logoutWhileNotAuthenticatedPrintsABenignLine() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter());
        h.repl().dispatch("connect node1:7900");
        assertThat(h.repl().dispatch("logout")).isTrue();
        assertThat(h.sink().toString()).contains("not logged in");
    }

    // --- failover: re-land across the member set on a lost landing node --------------------------

    @Test
    void failoverRelandsOnAnotherHealthyMemberKeepingTheCredential() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://n1:7900"), URI.create("http://n2:7900"));
        client.loginOutcome = new LoginOutcome.Success("jwt");
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("pw"));
        h.repl().dispatch("connect n1:7900,n2:7900");      // both healthy -> lands n1, members = [n1, n2]
        h.repl().dispatch("login alice");
        client.setHealthy(URI.create("http://n2:7900"));   // n1 goes down
        assertThat(h.repl().failover()).isTrue();
        assertThat(h.repl().session().landingNode()).isEqualTo(URI.create("http://n2:7900"));
        assertThat(h.repl().session().isAuthenticated()).isTrue();   // cluster-wide credential survives
        assertThat(h.repl().session().credential()).isEqualTo("jwt");
        assertThat(h.repl().prompt()).isEqualTo("cyntex(alice@n2:7900)> ");
    }

    @Test
    void failoverReconnectsToTheSameSingleNodeWhenItIsStillReachable() {
        // the failover path is not omitted for a single-node member list (L1 exercises the same mechanism)
        FakeControlPlane client = new FakeControlPlane(URI.create("http://n1:7900"));
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter());
        h.repl().dispatch("connect n1:7900");
        assertThat(h.repl().failover()).isTrue();
        assertThat(h.repl().session().landingNode()).isEqualTo(URI.create("http://n1:7900"));
        assertThat(h.repl().session().isConnected()).isTrue();
    }

    @Test
    void failoverWithNoReachableMemberLosesTheConnection() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://n1:7900"));
        client.loginOutcome = new LoginOutcome.Success("jwt");
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("pw"));
        h.repl().dispatch("connect n1:7900");
        h.repl().dispatch("login alice");
        client.setHealthy();   // nothing reachable
        assertThat(h.repl().failover()).isFalse();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(h.repl().session().isAuthenticated()).isFalse();
        assertThat(h.repl().prompt()).isEqualTo("cyntex(offline:cyn-work)> ");
    }

    @Test
    void failoverWhileOfflineIsANoOpAndProbesNothing() {
        FakeControlPlane client = new FakeControlPlane();
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter());
        assertThat(h.repl().failover()).isFalse();
        assertThat(h.repl().session().isConnected()).isFalse();
        assertThat(client.probed).isEmpty();
        assertThat(h.sink().toString()).isEmpty();   // a true no-op prints nothing (no "connection lost")
    }

    // --- online verbs: apply / get / ls routed to the server once authenticated -------------------

    /** Connects to a single healthy node and logs in, so a test starts from an authenticated session. */
    private static Harness onlineSession(Path workdir, FakeControlPlane client) {
        client.loginOutcome = new LoginOutcome.Success("jwt-tok");
        Harness h = harness(workdir, client, new ScriptedPrompter("pw"));
        h.repl().dispatch("connect node1:7900");
        h.repl().dispatch("login alice");
        return h;
    }

    @Test
    void getWhileAuthenticatedFetchesTheArtifactFromTheServer() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.getOutcome = new GetOutcome.Found(
                new RemoteArtifact("src_kfk", "source", "kind: source\nid: src_kfk\n"));
        Harness h = onlineSession(Path.of("cyn-work"), client);
        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("get src_kfk")).isTrue();
        String out = h.sink().toString().substring(mark);
        assertThat(out).contains("kind: source").contains("src_kfk");
        // the credential travels to the current landing node
        assertThat(client.getCalls).containsExactly("jwt-tok@http://node1:7900/src_kfk");
    }

    @Test
    void getForAMissingIdReportsNotFound() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.getOutcome = new GetOutcome.Absent();
        Harness h = onlineSession(Path.of("cyn-work"), client);
        h.repl().dispatch("get nope");
        assertThat(h.sink().toString()).contains("not found").contains("nope");
    }

    @Test
    void getWhileConnectedButNotAuthenticatedReportsAndDoesNotCallTheServer() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter());
        h.repl().dispatch("connect node1:7900");   // connected, never logged in
        assertThat(h.repl().dispatch("get x")).isTrue();
        // the not-authenticated state is a coded cli.* diagnostic naming the verb, not a bare string
        assertThat(h.sink().toString()).contains("cli.not-authenticated").contains("get");
        assertThat(client.getCalls).isEmpty();
    }

    @Test
    void anUnauthenticatedOnlineVerbRendersThroughTheSharedCatalogRendererNamingTheVerb() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter());
        h.repl().dispatch("connect node1:7900");   // connected, never logged in
        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("apply")).isTrue();
        String out = h.sink().toString().substring(mark);
        // rendered like every other coded diagnostic: an `error: <code>` header, the catalog message,
        // and the solution hint — proving it goes through the shared renderer, not a hand-rolled string
        assertThat(out).contains("error:").contains("cli.not-authenticated");
        assertThat(out).contains("apply");        // the {verb} placeholder is bound to the verb name
        assertThat(out).containsIgnoringCase("login");   // the solution points at the recovery verb
        assertThat(client.applyCalls).isEmpty();
    }

    @Test
    void getWithNoIdReportsMissingOperandAndDoesNotCallTheServer() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = onlineSession(Path.of("cyn-work"), client);
        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("get")).isTrue();
        assertThat(h.sink().toString().substring(mark)).contains("get:").contains("missing operand");
        assertThat(client.getCalls).isEmpty();
    }

    @Test
    void getRenderingAServerRejectionShowsTheCodeAndMessage() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.getOutcome = new GetOutcome.Rejected("control.forbidden", "You lack the grade.");
        Harness h = onlineSession(Path.of("cyn-work"), client);
        h.repl().dispatch("get x");
        assertThat(h.sink().toString()).contains("control.forbidden").contains("You lack the grade.");
    }

    @Test
    void lsWhileConnectedListsServerArtifactsNotTheLocalWorkspace() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.listOutcome = new ListOutcome.Listed(List.of(
                new RemoteArtifact("src_kfk", "source", "kind: source\n"),
                new RemoteArtifact("kfk2my", "pipeline", "kind: pipeline\n")));
        Harness h = onlineSession(Path.of("cyn-work"), client);
        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("ls")).isTrue();
        String out = h.sink().toString().substring(mark);
        assertThat(out).contains("src_kfk").contains("kfk2my").contains("source").contains("pipeline");
        assertThat(client.listCalls).containsExactly("jwt-tok@http://node1:7900?null");
    }

    @Test
    void lsWithAKindPassesTheKindFilterToTheServer() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.listOutcome = new ListOutcome.Listed(List.of());
        Harness h = onlineSession(Path.of("cyn-work"), client);
        h.repl().dispatch("ls source");
        assertThat(client.listCalls).containsExactly("jwt-tok@http://node1:7900?source");
    }

    // --- server-as-truth: a connected read verb sources the server store, never the local workspace ---

    @Test
    void onlineLsSourcesTheServerAndNeverTheLocalWorkspace(@TempDir Path base) throws Exception {
        copyWorkspace("/ws-valid", base);   // a real local workspace: source/src_kfk + pipeline/kfk2my
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.listOutcome = new ListOutcome.Listed(List.of());
        Harness h = harness(base, client, new ScriptedPrompter("pw"));
        // precondition: offline, ls really does read these local artifacts (so the guard below is load-bearing)
        h.repl().dispatch("ls");
        assertThat(h.sink().toString()).contains("src_kfk").contains("kfk2my");
        // once online, the same session's ls sources the (empty) server, not that local workspace
        client.loginOutcome = new LoginOutcome.Success("jwt-tok");
        h.repl().dispatch("connect node1:7900");
        h.repl().dispatch("login alice");
        int mark = h.sink().toString().length();
        h.repl().dispatch("ls");
        String out = h.sink().toString().substring(mark);
        assertThat(out).contains("no resources");                    // the empty server ...
        assertThat(out).doesNotContain("src_kfk").doesNotContain("kfk2my");   // ... not the local files it just listed
    }

    @Test
    void onlineGetSourcesTheServerCanonicalNotTheLocalWorkspaceFileWithTheSameId(@TempDir Path base) throws Exception {
        copyWorkspace("/ws-valid", base);   // a real local source/src_kfk.cyn.yml exists in the workspace
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.getOutcome = new GetOutcome.Found(new RemoteArtifact(
                "src_kfk", "source", "kind: source\nid: src_kfk\nserver_marker: REMOTE\n"));
        Harness h = onlineSession(base, client);
        int mark = h.sink().toString().length();
        h.repl().dispatch("get src_kfk");
        String out = h.sink().toString().substring(mark);
        // the server canonical is returned — carrying a marker the local file does not have — via the server call
        assertThat(out).contains("server_marker: REMOTE");
        assertThat(client.getCalls).containsExactly("jwt-tok@http://node1:7900/src_kfk");
    }

    @Test
    void applyWhileAuthenticatedSendsTheWorkspaceDraftsAndReportsTheOutcomes(@TempDir Path base) throws Exception {
        copyWorkspace("/ws-valid", base);
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.applyOutcome = new ApplyOutcome.Applied(List.of(
                new ApplyOutcome.Item("src_kfk", "source", "CREATED"),
                new ApplyOutcome.Item("kfk2my", "pipeline", "UNCHANGED")));
        Harness h = onlineSession(base, client);
        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("apply")).isTrue();
        String out = h.sink().toString().substring(mark);
        assertThat(out).contains("created").contains("src_kfk").contains("unchanged").contains("kfk2my");
        // one apply call carrying the three ws-valid drafts to the landing node with the credential
        assertThat(client.applyCalls).hasSize(1);
        assertThat(client.applyCalls.get(0)).startsWith("jwt-tok@http://node1:7900 x3");
    }

    @Test
    void applyReportsBenignlyWhenTheWorkspaceTreeCannotBeReadInsteadOfCrashing(@TempDir Path base) throws Exception {
        // a subdirectory that cannot be listed makes Files.walk raise UncheckedIOException mid-traversal;
        // apply must render a benign "cannot read" line, not let that escape and crash the REPL session
        Path locked = Files.createDirectory(base.resolve("source"));
        Files.writeString(locked.resolve("s.cyn.yml"), "kind: source\nid: x\n");
        assumeTrue(Files.getFileAttributeView(locked, PosixFileAttributeView.class) != null,
                "POSIX permissions required to make a subdirectory unreadable");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(locked), "permission enforcement required (skips when running as root)");
        try {
            FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
            Harness h = onlineSession(base, client);
            int mark = h.sink().toString().length();
            assertThat(h.repl().dispatch("apply")).isTrue();   // must not throw out of dispatch
            assertThat(h.sink().toString().substring(mark)).contains("apply:").contains("cannot read");
            assertThat(client.applyCalls).isEmpty();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void applyWithNoDraftsInTheWorkspaceReportsBenignlyAndDoesNotCallTheServer(@TempDir Path base) {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = onlineSession(base, client);
        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("apply")).isTrue();
        assertThat(h.sink().toString().substring(mark)).contains("apply:").contains("no");
        assertThat(client.applyCalls).isEmpty();
    }

    @Test
    void applyRenderingAServerRejectionShowsTheCodeAndMessage(@TempDir Path base) throws Exception {
        copyWorkspace("/ws-valid", base);
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.applyOutcome = new ApplyOutcome.Rejected("dsl.illegal-value", "Not a known kind.");
        Harness h = onlineSession(base, client);
        int mark = h.sink().toString().length();
        h.repl().dispatch("apply");
        assertThat(h.sink().toString().substring(mark)).contains("dsl.illegal-value").contains("Not a known kind.");
    }

    @Test
    void lsRenderingAServerRejectionShowsTheCodeAndMessageAndDoesNotFailOver() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.listOutcome = new ListOutcome.Rejected("control.forbidden", "You lack the grade.");
        Harness h = onlineSession(Path.of("cyn-work"), client);
        int probesBefore = client.probed.size();
        int mark = h.sink().toString().length();
        h.repl().dispatch("ls");
        String out = h.sink().toString().substring(mark);
        assertThat(out).contains("control.forbidden").contains("You lack the grade.");
        // a coded refusal is not a transport failure: it must not trigger failover
        assertThat(out).doesNotContain("reconnected").doesNotContain("connection lost");
        assertThat(client.probed.size()).isEqualTo(probesBefore);
    }

    @Test
    void lsWithAnEmptyServerStorePrintsNoResources() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.listOutcome = new ListOutcome.Listed(List.of());
        Harness h = onlineSession(Path.of("cyn-work"), client);
        int mark = h.sink().toString().length();
        h.repl().dispatch("ls");
        assertThat(h.sink().toString().substring(mark)).contains("no resources");
    }

    @Test
    void applyWhileOfflineFallsThroughToTheConnectionRequiredNotice() {
        Harness h = harness();   // offline: apply is a connected verb, so the offline notice fires
        assertThat(h.repl().dispatch("apply")).isTrue();
        assertThat(h.sink().toString()).contains("requires a connection");
    }

    @Test
    void getWhileOfflineFallsThroughToTheConnectionRequiredNotice() {
        Harness h = harness();   // offline: get is a connected verb, discoverable rather than unknown
        assertThat(h.repl().dispatch("get x")).isTrue();
        assertThat(h.sink().toString()).contains("requires a connection");
    }

    // --- pipeline lifecycle verbs route online to POST /api/pipelines/{id}:{verb} ------------------

    @Test
    void startWhileAuthenticatedRoutesToTheServerAndPrintsTheNewState() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.lifecycleOutcome = new LifecycleOutcome.Accepted("pl1", "RUNNING", "rev-abc");
        Harness h = onlineSession(Path.of("cyn-work"), client);
        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("start pl1")).isTrue();
        assertThat(h.sink().toString().substring(mark)).contains("pl1").contains("running");
        assertThat(client.lifecycleCalls).containsExactly("jwt-tok@http://node1:7900 start pl1");
    }

    @Test
    void theFourLifecycleVerbsEachRouteToTheirOwnServerVerb() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.lifecycleOutcome = new LifecycleOutcome.Accepted("pl1", "RUNNING", "rev-abc");
        Harness h = onlineSession(Path.of("cyn-work"), client);
        h.repl().dispatch("start pl1");
        h.repl().dispatch("pause pl1");
        h.repl().dispatch("resume pl1");
        h.repl().dispatch("stop pl1");
        assertThat(client.lifecycleCalls).containsExactly(
                "jwt-tok@http://node1:7900 start pl1",
                "jwt-tok@http://node1:7900 pause pl1",
                "jwt-tok@http://node1:7900 resume pl1",
                "jwt-tok@http://node1:7900 stop pl1");
    }

    @Test
    void aLifecycleVerbWithoutAPipelineIdIsABenignUsageLineAndCallsNothing() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = onlineSession(Path.of("cyn-work"), client);
        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("start")).isTrue();
        assertThat(h.sink().toString().substring(mark)).contains("start").contains("missing operand");
        assertThat(client.lifecycleCalls).isEmpty();
    }

    @Test
    void aLifecycleVerbRejectionShowsTheCodeAndMessageAndDoesNotFailOver() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.lifecycleOutcome = new LifecycleOutcome.Rejected(
                "lifecycle.illegal-transition", "Cannot pause a pipeline that is not running.");
        Harness h = onlineSession(Path.of("cyn-work"), client);
        int probesBefore = client.probed.size();
        int mark = h.sink().toString().length();
        h.repl().dispatch("pause pl1");
        String out = h.sink().toString().substring(mark);
        assertThat(out).contains("lifecycle.illegal-transition").contains("Cannot pause");
        // a coded refusal is not a transport failure: it must not trigger failover
        assertThat(out).doesNotContain("reconnected").doesNotContain("connection lost");
        assertThat(client.probed.size()).isEqualTo(probesBefore);
    }

    @Test
    void anUnauthenticatedLifecycleVerbSaysRunLoginAndCallsNothing() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("pw"));
        h.repl().dispatch("connect node1:7900");   // connected, not authenticated
        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("start pl1")).isTrue();
        assertThat(h.sink().toString().substring(mark)).contains("login");
        assertThat(client.lifecycleCalls).isEmpty();
    }

    @Test
    void startWhileOfflineFallsThroughToTheConnectionRequiredNotice() {
        Harness h = harness();   // offline: start is a connected verb, discoverable rather than unknown
        assertThat(h.repl().dispatch("start pl1")).isTrue();
        assertThat(h.sink().toString()).contains("requires a connection");
    }

    @Test
    void aLifecycleVerbFailsOverToAHealthyMemberAndRetriesOnceOnTheNewNode() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://n1:7900"), URI.create("http://n2:7900"));
        client.loginOutcome = new LoginOutcome.Success("jwt-tok");
        client.lifecycleOutcome = new LifecycleOutcome.Accepted("pl1", "RUNNING", "rev-abc");
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("pw"));
        h.repl().dispatch("connect n1:7900,n2:7900");   // land n1, members [n1, n2]
        h.repl().dispatch("login alice");
        client.setHealthy(URI.create("http://n2:7900"));   // n1 goes down before the request
        int mark = h.sink().toString().length();
        h.repl().dispatch("start pl1");
        String out = h.sink().toString().substring(mark);
        assertThat(out).contains("reconnected to n2:7900");
        assertThat(out).contains("pl1").contains("running");
    }

    // --- online verbs fail over on a request the landing node cannot answer ------------------------

    @Test
    void anOnlineVerbRejectsDashOptionsRatherThanMisreadingThemAsOperands() {
        // `-o json` must not be silently read as a kind filter (which would list nothing); connected verbs
        // take positional operands only until structured output lands for them
        FakeControlPlane client = new FakeControlPlane(URI.create("http://node1:7900"));
        client.listOutcome = new ListOutcome.Listed(List.of());
        Harness h = onlineSession(Path.of("cyn-work"), client);
        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("ls -o json")).isTrue();
        assertThat(h.sink().toString().substring(mark)).contains("options are not supported");
        assertThat(client.listCalls).isEmpty();
    }

    @Test
    void anOnlineVerbFailsOverToAHealthyMemberAndRetriesOnceOnTheNewNode() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://n1:7900"), URI.create("http://n2:7900"));
        client.loginOutcome = new LoginOutcome.Success("jwt-tok");
        client.getOutcome = new GetOutcome.Found(new RemoteArtifact("src_kfk", "source", "kind: source\n"));
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("pw"));
        h.repl().dispatch("connect n1:7900,n2:7900");   // land n1, members [n1, n2]
        h.repl().dispatch("login alice");
        client.setHealthy(URI.create("http://n2:7900"));   // n1 goes down before the request

        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("get src_kfk")).isTrue();
        String out = h.sink().toString().substring(mark);
        assertThat(out).contains("reconnected to n2:7900").contains("kind: source");
        assertThat(h.repl().session().landingNode()).isEqualTo(URI.create("http://n2:7900"));
        // the verb was attempted on n1 (unreachable) then retried on n2 after failover, credential intact
        assertThat(client.getCalls).containsExactly(
                "jwt-tok@http://n1:7900/src_kfk", "jwt-tok@http://n2:7900/src_kfk");
    }

    @Test
    void anOnlineVerbWithNoReachableMemberLosesTheConnectionAndReportsItOnce() {
        FakeControlPlane client = new FakeControlPlane(URI.create("http://n1:7900"));
        client.loginOutcome = new LoginOutcome.Success("jwt-tok");
        client.getOutcome = new GetOutcome.Found(new RemoteArtifact("x", "source", "kind: source\n"));
        Harness h = harness(Path.of("cyn-work"), client, new ScriptedPrompter("pw"));
        h.repl().dispatch("connect n1:7900");
        h.repl().dispatch("login alice");
        client.setHealthy();   // every member is down

        int mark = h.sink().toString().length();
        assertThat(h.repl().dispatch("get x")).isTrue();
        String out = h.sink().toString().substring(mark);
        assertThat(out).contains("connection lost");        // failover reported the loss
        assertThat(out).doesNotContain("request failed");   // and it is not double-reported
        assertThat(h.repl().session().isConnected()).isFalse();
    }
}
