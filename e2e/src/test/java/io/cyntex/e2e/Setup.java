package io.cyntex.e2e;

import java.util.List;

/**
 * The connection-provisioning facet. The three keys are three real product verbs in dependency
 * order: a connector jar must be registered before a source resource referencing it can be applied,
 * and a source model must be discovered before a target table can be derived from it.
 *
 * @param connectors connector ids whose runtime jars are registered (content-hash idempotent)
 * @param apply product resource files applied before the pipeline; sources may not be inlined, so a
 *     pipeline can only reference them by id once they exist
 * @param discover resource ids whose source model is discovered, feeding target-table creation
 */
public record Setup(List<String> connectors, List<String> apply, List<String> discover) {

    public static final Setup NONE = new Setup(List.of(), List.of(), List.of());

    public Setup {
        connectors = List.copyOf(connectors);
        apply = List.copyOf(apply);
        discover = List.copyOf(discover);
    }
}
