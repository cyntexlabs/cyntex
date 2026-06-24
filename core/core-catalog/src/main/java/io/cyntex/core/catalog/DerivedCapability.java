package io.cyntex.core.catalog;

import java.util.EnumSet;
import java.util.Set;

/**
 * The connector capabilities the catalog derives a mode or write semantics from. Each maps to the
 * id a connector registers via {@code registerCapabilities} (the snake_case function field name);
 * the classloading derive tool produces those ids and {@link #fromCapabilityIds} narrows them to
 * this vocabulary. Capabilities outside this set do not affect catalog modes and are ignored.
 */
public enum DerivedCapability {

    /** Bounded read of current rows — derives the {@code snapshot} mode (for any source). */
    BATCH_READ("batch_read_function"),
    /** Unbounded read — derives {@code cdc} by default (database connectors); message systems,
     *  SaaS and file connectors register it too, so they must declare their real mode instead. */
    STREAM_READ("stream_read_function"),
    /** Record write — makes the connector a sink. */
    WRITE_RECORD("write_record_function");

    private final String capabilityId;

    DerivedCapability(String capabilityId) {
        this.capabilityId = capabilityId;
    }

    /** The {@code registerCapabilities} id this capability corresponds to. */
    public String capabilityId() {
        return capabilityId;
    }

    /** Narrows a set of registered capability ids to the capabilities the catalog cares about. */
    public static Set<DerivedCapability> fromCapabilityIds(Set<String> capabilityIds) {
        EnumSet<DerivedCapability> result = EnumSet.noneOf(DerivedCapability.class);
        for (DerivedCapability capability : values()) {
            if (capabilityIds.contains(capability.capabilityId)) {
                result.add(capability);
            }
        }
        return result;
    }
}
