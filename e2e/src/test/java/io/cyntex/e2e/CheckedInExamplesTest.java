package io.cyntex.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Holds the published examples to the parser and the schema that are published beside them.
 *
 * <p>An example is the one artifact an author - especially a model reading the repository as ground
 * truth - copies before writing anything of their own. That makes a stale example worse than none: it
 * teaches a shape the executor no longer runs, and it does so with the authority of a checked-in file.
 *
 * <p>The example is held from two sides on purpose. The executor running it proves the shape still
 * works; this test proves the same bytes are what the published parser and the published schema
 * accept. Neither implies the other: an example the executor never loads can rot unnoticed, and one
 * that runs may still contradict the schema an author is told to trust.
 *
 * <p>The sweep is asserted to find something. A sweep over a directory that has been moved or renamed
 * finds no file, has nothing to disagree with, and reports the silence as success.
 */
class CheckedInExamplesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The published schema as it sits on disk, which is the copy an author would open. */
    private static final JsonSchema SCHEMA = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(Examples.read(Path.of("spec", "e2e-spec.schema.json")));

    static List<Path> specifications() {
        return Examples.specifications();
    }

    @Test
    void theSweepFindsThePublishedExamples() {
        assertThat(Examples.specifications())
                .as("no specification under %s/ - a sweep that finds nothing agrees with everything", Examples.ROOT)
                .isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("specifications")
    void theParserAcceptsThePublishedExample(Path specification) {
        String yaml = Examples.read(specification);
        assertThat(catchThrowable(() -> EnvelopeParser.parse(yaml)))
                .as("the executor must be able to run what is published as an example:%n%s", yaml)
                .isNull();
    }

    @ParameterizedTest
    @MethodSource("specifications")
    void theSchemaAcceptsThePublishedExample(Path specification) {
        String yaml = Examples.read(specification);
        assertThat(validate(yaml))
                .as("the published schema must accept the published example, or one of them is lying:%n%s", yaml)
                .isEmpty();
    }

    private Set<ValidationMessage> validate(String yaml) {
        Object tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        return SCHEMA.validate(JSON.valueToTree(tree));
    }
}
