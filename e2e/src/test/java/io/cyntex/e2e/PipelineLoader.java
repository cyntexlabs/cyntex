package io.cyntex.e2e;

/**
 * Resolves the {@code pipeline:} reference to the pipeline's id. The reference names a product
 * {@code .cyn.yml}, so resolving it is a parse by the product's own parser rather than a guess from
 * the file name.
 */
@FunctionalInterface
public interface PipelineLoader {

    String resolvePipelineId(String pipelineRef);
}
