package io.cyntex.runtime.srs;

import java.util.List;
import java.util.Objects;

/**
 * What provisioning a cdc source did to its mining chain. {@code merged} is false when this source opened
 * the chain and true when the chain already existed and the source was force-merged onto it — the signal a
 * caller surfaces as "this config coincides with an already-running capture, so it shares that chain rather
 * than mining the source a second time". {@code tables} is the chain's table set after this source's streams
 * were unioned in.
 */
public record ProvisionOutcome(MiningChainId chainId, boolean merged, List<String> tables) {

    public ProvisionOutcome {
        Objects.requireNonNull(chainId, "chainId");
        tables = List.copyOf(tables);
    }
}
