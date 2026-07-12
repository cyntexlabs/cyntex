package io.cyntex.runtime.srs;

import java.util.List;
import java.util.Objects;

/**
 * What a source-level teardown of a mining chain would affect, computed before it runs so the boundary is
 * never crossed implicitly: the consumer pipelines that would lose the shared change stream, and the
 * per-table ring names that would be destroyed. A read-only plan the caller presents for informed consent;
 * the destructive act is a separate explicit step.
 */
public record SourceTeardownPlan(MiningChainId chainId, List<String> affectedConsumers, List<String> ringNames) {

    public SourceTeardownPlan {
        Objects.requireNonNull(chainId, "chainId");
        affectedConsumers = List.copyOf(affectedConsumers);
        ringNames = List.copyOf(ringNames);
    }
}
