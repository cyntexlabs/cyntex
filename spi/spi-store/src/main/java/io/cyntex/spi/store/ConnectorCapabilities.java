package io.cyntex.spi.store;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The raw capabilities a connector declares: the ids its registerCapabilities registers (the
 * snake_case function field names, e.g. {@code batch_read_function}). This is the bridge's faithful
 * output — the registered ids and nothing more; turning them into source modes and sink capability is
 * the catalog's job, not the bridge's. An immutable value carrying no connector-framework types.
 *
 * <p>{@code capabilityIds} is held as a sorted, unmodifiable, defensive copy so iteration is
 * deterministic; a null set is normalized to empty.
 */
public record ConnectorCapabilities(Set<String> capabilityIds) {

    public ConnectorCapabilities {
        capabilityIds = capabilityIds == null
                ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(capabilityIds));
    }
}
