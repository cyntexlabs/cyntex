package io.cyntex.adapters.pdk;

import java.util.Objects;

/**
 * What self-scan reads off a connector artifact: the entry class its {@code @TapConnectorClass}
 * annotation marks, the spec resource that annotation names (its path and text), and the PDK API
 * version the jar manifest declares ({@code null} if it declares none). Enough to build a
 * {@link ConnectorRef} for the bridge and to hand the spec to catalog normalization.
 */
public record IntrospectedConnector(String className, String pdkApiVersion, String specPath, String spec) {

    public IntrospectedConnector {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(specPath, "specPath");
        Objects.requireNonNull(spec, "spec");
    }
}
