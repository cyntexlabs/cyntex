package io.cyntex.control.core;

import io.cyntex.core.catalog.VisibleWhen;

import java.util.List;

/** A safe declarative visibility rule distilled from connector form metadata. */
public record ConnectorVisibilityView(String controllingField, List<String> equalsAnyOf) {

    static ConnectorVisibilityView of(VisibleWhen visibility) {
        return visibility == null
                ? null
                : new ConnectorVisibilityView(visibility.controllingField(), visibility.equalsAnyOf());
    }
}
