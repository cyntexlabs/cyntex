package io.cyntex.control.core;

import io.cyntex.core.catalog.ConfigField;

import java.util.List;

/** One normalized connector configuration field exposed to presentation faces. */
public record ConnectorConfigFieldView(
        String name,
        String type,
        String label,
        boolean required,
        String defaultValue,
        boolean secret,
        List<ConnectorOptionView> options,
        ConnectorVisibilityView visibleWhen) {

    static ConnectorConfigFieldView of(ConfigField field) {
        return new ConnectorConfigFieldView(
                field.name(),
                field.type().yaml(),
                ConnectorOptionView.english(field.label(), field.name()),
                field.required(),
                field.defaultValue(),
                field.secret(),
                field.options().stream().map(ConnectorOptionView::of).toList(),
                ConnectorVisibilityView.of(field.visibleWhen()));
    }
}
