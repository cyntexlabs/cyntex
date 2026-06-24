package io.cyntex.core.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Malformed-YAML wrapping. A {@code .cyn.yml} whose YAML does not even parse must surface as a coded
 * {@link DslException} ({@code dsl.malformed-yaml}) — never a raw snakeyaml stack trace at the user
 * boundary, since every user-facing / diagnosable error must carry a code. This
 * is the direct witness for {@code malformed-yaml}: a syntax error cannot live in the well-formed
 * corpus, so the corpus-vocabulary gate exempts it and it is proven here instead.
 */
class DslMalformedYamlTest {

    private final DslParser parser = new DslParser();

    @Test
    @DisplayName("a syntax error parses into a coded dsl.malformed-yaml exception, not a raw snakeyaml throw")
    void syntaxErrorBecomesACodedException() {
        DslException ex = catchThrowableOfType(
                () -> parser.parse("[unterminated\n"), DslException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.code().code()).isEqualTo("dsl.malformed-yaml");
        // the snakeyaml problem is carried as the detail argument, and the location is captured
        assertThat(ex.args().get("detail").toString()).isNotBlank();
        assertThat(ex.line()).isPositive();
        // pre-semantic: the args carry ONLY detail — no empty `path` leaks in to contradict placeholders()
        assertThat(ex.args()).containsOnlyKeys("detail");
    }

    @Test
    @DisplayName("the workspace loader attributes a malformed file to its source name")
    void workspaceLoaderAttributesMalformedFileToItsSource(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("source"));
        Files.writeString(dir.resolve("source").resolve("broken.cyn.yml"), "[unterminated\n");

        DslException ex = catchThrowableOfType(() -> WorkspaceLoader.load(dir), DslException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.code().code()).isEqualTo("dsl.malformed-yaml");
        assertThat(ex.source()).isEqualTo("broken.cyn.yml");
    }
}
