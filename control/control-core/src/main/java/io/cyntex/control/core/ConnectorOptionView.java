package io.cyntex.control.core;

import io.cyntex.core.catalog.EnumOption;

import java.util.Map;

/** One normalized option for a connector configuration field. */
public record ConnectorOptionView(String value, String label) {

    static ConnectorOptionView of(EnumOption option) {
        return new ConnectorOptionView(option.value(), english(option.label(), option.value()));
    }

    static String english(Map<String, String> labels, String fallback) {
        String label = labels.get("en_US");
        return label == null || label.isBlank() ? fallback : label;
    }
}
