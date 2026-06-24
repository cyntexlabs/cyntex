package io.cyntex.core.catalog;

import java.util.Collections;
import java.util.List;

/**
 * A field's conditional visibility, distilled from the connector's Formily {@code x-reactions}: the
 * field is shown only when another field's value is one of {@code equalsAnyOf}. Boolean controllers
 * carry {@code "true"}/{@code "false"}. Reactions we cannot reduce to this shape are dropped (the
 * field then has no rule), so this is a best-effort, lossy projection of the original logic.
 */
public record VisibleWhen(String controllingField, List<String> equalsAnyOf) {

    public VisibleWhen {
        equalsAnyOf = equalsAnyOf == null
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(equalsAnyOf));
    }
}
