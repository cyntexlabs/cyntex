package io.cyntex.core.catalog;

import java.util.Collections;
import java.util.List;

/**
 * The structural facts a normalizer reads from a connector's spec, before merging with the derived
 * capability bitmap. Identity, group guess, config form, the DML signals a sink's write semantics
 * derive from, and any declared {@code cyntex.modes}. Capability-dependent fields (resolved modes,
 * sink capability) are added later by the entry assembler.
 */
public record NormalizedSpec(
        String id,
        String name,
        String displayName,
        String icon,
        ConnectorGroup tagGroup,
        List<ConfigField> config,
        List<String> dmlInsertAlternatives,
        boolean hasDmlUpdatePolicy,
        List<String> declaredModes) {

    public NormalizedSpec {
        config = config == null ? List.of() : Collections.unmodifiableList(List.copyOf(config));
        dmlInsertAlternatives = dmlInsertAlternatives == null
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(dmlInsertAlternatives));
        // declaredModes stays nullable: null means "no declaration" (derive defaults apply),
        // distinct from an explicit empty declaration.
        declaredModes = declaredModes == null ? null : Collections.unmodifiableList(List.copyOf(declaredModes));
    }
}
