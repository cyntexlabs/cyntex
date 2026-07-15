package io.cyntex.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the generated specification artifacts to the checked-in files byte-for-byte.
 *
 * <p>The artifacts under {@code spec/} are what an author reads to learn what may be written, so a
 * diff here is a real change to the authoring surface: regenerate with
 * {@code -Dcyntex.e2e.spec.update=true}, then review the diff. Hand-editing either file is the one
 * thing that must not happen - a hand-kept description of a parser is the drift this whole
 * arrangement exists to prevent, and it drifts silently, because a stale schema still looks like a
 * schema.
 */
class SpecArtifactTest {

    private static final boolean UPDATE = Boolean.getBoolean("cyntex.e2e.spec.update");

    private static final Path SCHEMA = Path.of("spec", "e2e-spec.schema.json");
    private static final Path VOCABULARY = Path.of("spec", "matchers.json");

    @Test
    void theSchemaMatchesTheCheckedInArtifact() throws IOException {
        lock(SCHEMA, SpecGenerator.schema());
    }

    @Test
    void theVocabularyMatchesTheCheckedInArtifact() throws IOException {
        lock(VOCABULARY, SpecGenerator.vocabulary());
    }

    @Test
    void specUpdateToggleIsOffDuringNormalRuns() {
        // The regenerate path rewrites the artifacts and skips the byte assertions. Were the toggle set
        // during a normal run, a real change to the authoring surface would be silently rebaselined and
        // pass. This guard makes any run with the toggle RED, so regeneration is always deliberate.
        assertThat(UPDATE)
                .as("cyntex.e2e.spec.update must not be set during a normal run — it rewrites the artifacts")
                .isFalse();
    }

    private static void lock(Path artifact, String generated) throws IOException {
        if (UPDATE) {
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, generated);
            return;
        }
        assertThat(Files.exists(artifact))
                .as("%s missing — regenerate with -Dcyntex.e2e.spec.update=true", artifact)
                .isTrue();
        assertThat(Files.readString(artifact))
                .as("%s drifted from the executor — regenerate with -Dcyntex.e2e.spec.update=true, "
                        + "then review the diff", artifact)
                .isEqualTo(generated);
    }
}
