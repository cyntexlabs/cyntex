package io.cyntex.control.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One registered control operation: the single source of truth for a verb the process can perform.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code id} — a globally unique, dot-scoped {@code <domain>.<verb>} identifier (e.g.
 *       {@code artifact.apply}); each face derives its own name from this canonical form.
 *   <li>{@code scope} — the capability grade required to invoke it.
 *   <li>{@code audited} — whether an invocation must record an audit entry (write / admin operations do;
 *       a failed audit write means the operation does not execute).
 *   <li>{@code schema} — references into the shared schema for request and result, or {@code null}.
 *   <li>{@code exposure} — the stage at which this operation is open on each face. An absent face means
 *       "not exposed there"; the map is defensively copied and unmodifiable.
 * </ul>
 *
 * <p>Only concrete, one-to-one operations are modelled here. Face-level composition sugar (a verb that
 * chains several operations) and offline-only local computation are not operations and get no entry —
 * a face may compose registered operations, it may not invent semantics.
 */
public record Operation(String id, Scope scope, boolean audited, SchemaRef schema, Map<Frontend, Maturity> exposure) {

    private static final Pattern ID = Pattern.compile("[a-z0-9]+(\\.[a-z0-9-]+)+");

    public Operation {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("operation id must be non-blank");
        }
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("operation id must be dot-scoped <domain>.<verb>: " + id);
        }
        if (scope == null) {
            throw new IllegalArgumentException("operation scope must be set: " + id);
        }
        EnumMap<Frontend, Maturity> copy = new EnumMap<>(Frontend.class);
        if (exposure != null) {
            for (Map.Entry<Frontend, Maturity> e : exposure.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    throw new IllegalArgumentException(
                            "operation exposure must not carry a null frontend or maturity: " + id);
                }
                copy.put(e.getKey(), e.getValue());
            }
        }
        exposure = Collections.unmodifiableMap(copy);
    }
}
