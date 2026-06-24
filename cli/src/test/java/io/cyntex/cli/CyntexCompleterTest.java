package io.cyntex.cli;

import io.cyntex.core.schema.SchemaNavigator;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REPL Tab completer: verbs at the start, grammar field paths after {@code explain}, filesystem
 * paths after {@code validate}, and nothing for verbs whose argument completion belongs to a later
 * slice. {@link CyntexCompleter#candidates} is the pure seam; a dumb-terminal {@code LineReader}
 * exercises the JLine adapter end to end.
 */
class CyntexCompleterTest {

    private final CyntexCompleter completer =
            CyntexCompleter.forRepl(Cli.newCommandLine(), SchemaNavigator.bundled());

    @Test
    void completesVerbsAtTheStart() {
        assertThat(completer.candidates(List.of(""), 0))
                .contains("validate", "new", "explain", "apply", "help", "exit");
    }

    @Test
    void filtersVerbsByPrefix() {
        assertThat(completer.candidates(List.of("va"), 0)).containsExactly("validate");
    }

    @Test
    void completesExplainFieldPaths() {
        assertThat(completer.candidates(List.of("explain", "source.m"), 1))
                .containsExactly("source.metadata", "source.mode");
    }

    @Test
    void completesExplainTopLevelKinds() {
        assertThat(completer.candidates(List.of("explain", ""), 1))
                .contains("source", "pipeline", "serve");
    }

    @Test
    void offersNoArgumentCompletionForABareNewPositionalOrConnectedVerbs() {
        // 'new <TAB>' with no option flag yields nothing — the wizard drives that path
        assertThat(completer.candidates(List.of("new", ""), 1)).isEmpty();
        assertThat(completer.candidates(List.of("apply", ""), 1)).isEmpty();
    }

    @Test
    void completesConnectorIdsAfterTheConnectorOption() {
        assertThat(completer.candidates(List.of("new", "--connector", ""), 2))
                .contains("mysql", "mongodb", "postgres");
        assertThat(completer.candidates(List.of("new", "-c", "my"), 2))
                .contains("mysql")
                .allMatch(c -> c.startsWith("my"));
    }

    @Test
    void completesModesNarrowedByTheConnectorOnTheLine() {
        // mysql is a database connector with a trustworthy matrix [cdc, snapshot]
        assertThat(completer.candidates(List.of("new", "-c", "mysql", "-m", ""), 4))
                .containsExactlyInAnyOrder("cdc", "snapshot");
    }

    @Test
    void completesEveryModeWhenNoConnectorConstrainsThem() {
        assertThat(completer.candidates(List.of("new", "--mode", ""), 2))
                .contains("cdc", "snapshot", "stream", "api", "file");
    }

    @Test
    void completesKindValues() {
        assertThat(completer.candidates(List.of("new", "--kind", ""), 2))
                .containsExactlyInAnyOrder("pipeline", "serve", "source", "transform", "view");
    }

    @Test
    void completesTransformTypeValues() {
        assertThat(completer.candidates(List.of("new", "--type", ""), 2))
                .containsExactlyInAnyOrder("filter", "map", "js", "union", "nest", "join");
        // prefix-filtered like every other option value
        assertThat(completer.candidates(List.of("new", "--type", "j"), 2))
                .containsExactlyInAnyOrder("js", "join");
    }

    @Test
    void doesNotCompleteConnectorsForOtherVerbsOptionValues() {
        // catalog-backed option completion is gated to the new verb; another verb's options get nothing
        assertThat(completer.candidates(List.of("validate", "-c", ""), 2)).isEmpty();
    }

    @Test
    void completesExplainPathEvenWhenAnOptionPrecedesIt() {
        // the field path is the first positional even though -o json shifts its word index
        assertThat(completer.candidates(List.of("explain", "-o", "json", "source.m"), 3))
                .containsExactly("source.metadata", "source.mode");
    }

    @Test
    void doesNotCompleteFieldPathsInTheOptionValueSlot() {
        // 'explain -o <TAB>' completes the -o value, not a grammar field path
        assertThat(completer.candidates(List.of("explain", "-o", ""), 2)).isEmpty();
    }

    @Test
    void offersNoPathCompletionForASecondPositional() {
        assertThat(completer.candidates(List.of("explain", "source.mode", ""), 2)).isEmpty();
    }

    @Test
    void jlineAdapterOffersFilesForAValidatePathAfterAnOption(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("workspace.cyn.yml"));
        assertThat(completeLine("validate -o json " + dir + "/"))
                .anyMatch(c -> c.contains("workspace.cyn.yml"));
    }

    @Test
    void jlineAdapterYieldsExplainPathCandidates() throws IOException {
        assertThat(completeLine("explain source.mo")).contains("source.mode");
    }

    @Test
    void jlineAdapterCompletesTheNewConnectorOption() throws IOException {
        assertThat(completeLine("new --connector my")).contains("mysql");
    }

    @Test
    void jlineAdapterCompletesTheNewModeNarrowedByConnector() throws IOException {
        // the mode path depends on optionValue() scanning the JLine-parsed words for --connector
        assertThat(completeLine("new -c mysql -m ")).containsExactlyInAnyOrder("cdc", "snapshot");
    }

    @Test
    void jlineAdapterCompletesTheNewKindOption() throws IOException {
        assertThat(completeLine("new --kind "))
                .containsExactlyInAnyOrder("pipeline", "serve", "source", "transform", "view");
    }

    @Test
    void jlineAdapterCompletesTheNewTypeOption() throws IOException {
        assertThat(completeLine("new --kind transform --type "))
                .containsExactlyInAnyOrder("filter", "map", "js", "union", "nest", "join");
    }

    @Test
    void jlineAdapterOffersFilesForAValidatePath(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("workspace.cyn.yml"));
        assertThat(completeLine("validate " + dir + "/"))
                .anyMatch(c -> c.contains("workspace.cyn.yml"));
    }

    private List<String> completeLine(String line) throws IOException {
        try (Terminal terminal = TerminalBuilder.builder().dumb(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            ParsedLine parsed = reader.getParser().parse(line, line.length());
            List<Candidate> candidates = new ArrayList<>();
            completer.complete(reader, parsed, candidates);
            return candidates.stream().map(Candidate::value).toList();
        }
    }
}
