package io.cyntex.core.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the generated {@code cyntex/v1} schema to the checked-in artifact byte-for-byte. The
 * artifact under {@code src/main/resources} is what the runtime bundles and serves, so a diff
 * here is a real change to the public schema: regenerate with {@code -Dcyntex.schema.update=true},
 * then review the diff.
 */
class SchemaArtifactTest {

    private static final Path ARTIFACT =
            Path.of("src", "main", "resources", "schema", "cyntex-v1.schema.json");
    private static final boolean UPDATE = Boolean.getBoolean("cyntex.schema.update");

    private final SchemaGenerator generator = new SchemaGenerator();

    @Test
    void generatedSchemaMatchesTheCheckedInArtifact() throws IOException {
        String generated = generator.generate();
        if (UPDATE) {
            Files.createDirectories(ARTIFACT.getParent());
            Files.writeString(ARTIFACT, generated);
            return;
        }
        assertThat(Files.exists(ARTIFACT))
                .as("schema artifact missing — regenerate with -Dcyntex.schema.update=true")
                .isTrue();
        assertThat(Files.readString(ARTIFACT)).isEqualTo(generated);
    }

    @Test
    void schemaUpdateToggleIsOffDuringNormalRuns() {
        // The regenerate path rewrites the artifact and skips the byte assertion. Were the toggle
        // set during a normal run, a real schema regression would be silently rebaselined and pass.
        // This guard makes any run with the toggle RED, so regeneration is always deliberate.
        assertThat(UPDATE)
                .as("cyntex.schema.update must not be set during a normal run — it rewrites the artifact")
                .isFalse();
    }
}
