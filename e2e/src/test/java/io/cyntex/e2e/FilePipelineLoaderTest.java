package io.cyntex.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code pipeline:} reference is resolved by the product's own parser. A second, test-local
 * reading of the DSL would be a copy free to drift from the grammar it claims to exercise, and a
 * specification that drifted from the product would still pass while testing nothing real.
 */
class FilePipelineLoaderTest {

    private static final String PIPELINE =
            """
            version: cyntex/v1
            kind: pipeline
            id: mongo2mongo
            source: src_mongo
            serve:
              from: /.*/
              sync:
                - { source: tgt_mongo, write_mode: upsert }
            """;

    private static final String SOURCE =
            """
            version: cyntex/v1
            kind: source
            id: src_mongo
            connector: mongodb
            config: { uri: "mongodb://127.0.0.1:27017/demo" }
            """;

    @TempDir Path workspace;

    @Test
    void resolvesThePipelineIdByParsingTheReferencedProductFile() throws IOException {
        write("mongo2mongo.cyn.yml", PIPELINE);

        String id = new FilePipelineLoader(workspace).resolvePipelineId("mongo2mongo.cyn.yml");

        assertThat(id).isEqualTo("mongo2mongo");
    }

    @Test
    void namesTheKindItFoundWhenTheReferenceIsNotAPipeline() throws IOException {
        write("src_mongo.cyn.yml", SOURCE);

        assertThatThrownBy(() -> new FilePipelineLoader(workspace).resolvePipelineId("src_mongo.cyn.yml"))
                .isInstanceOf(EnvelopeException.class)
                // The kind is the computed part: asserting only the boilerplate would pass even if
                // the parser reported no kind at all.
                .hasMessage("src_mongo.cyn.yml must declare a pipeline, found kind: source");
    }

    @Test
    void rejectsAReferenceToAMissingFile() {
        assertThatThrownBy(() -> new FilePipelineLoader(workspace).resolvePipelineId("absent.cyn.yml"))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("absent.cyn.yml");
    }

    @Test
    void carriesTheProductDiagnosticIntoTheAuthoringError() throws IOException {
        write("broken.cyn.yml", "version: cyntex/v1\nkind: pipeline\nid: broken\nnonsense: true\n");

        assertThatThrownBy(() -> new FilePipelineLoader(workspace).resolvePipelineId("broken.cyn.yml"))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("broken.cyn.yml does not parse")
                // The product's own coded diagnostic must survive into the message, otherwise the
                // author is told only that something, somewhere, is wrong.
                .hasMessageContaining("dsl.unknown-field")
                .hasMessageContaining("nonsense");
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(workspace.resolve(name), content);
    }
}
