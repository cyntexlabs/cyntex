package io.cyntex.e2e;

import io.cyntex.core.dsl.DslException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves a {@code pipeline:} reference against a workspace directory, reading the file with the
 * product's own parser. Paths are relative to the specification, so a specification and the
 * resources it names travel together.
 */
public final class FilePipelineLoader implements PipelineLoader {

    private final Path workspace;
    private final DslParser parser = new DslParser();

    public FilePipelineLoader(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public String resolvePipelineId(String pipelineRef) {
        Resource resource = parse(pipelineRef, read(pipelineRef));
        if (!(resource instanceof PipelineResource pipeline)) {
            throw new EnvelopeException(
                    pipelineRef + " must declare a pipeline, found kind: " + resource.kind());
        }
        return pipeline.id();
    }

    private String read(String pipelineRef) {
        try {
            return Files.readString(workspace.resolve(pipelineRef));
        } catch (IOException e) {
            throw new EnvelopeException("cannot read the pipeline referenced as " + pipelineRef, e);
        }
    }

    /**
     * Only a DSL error becomes an authoring error. Anything else thrown by the parser is a defect in
     * the parser, and blaming the author's YAML for it would bury the real fault.
     */
    private Resource parse(String pipelineRef, String yaml) {
        try {
            return parser.parse(yaml);
        } catch (DslException e) {
            throw new EnvelopeException(pipelineRef + " does not parse: " + e.getMessage(), e);
        }
    }
}
