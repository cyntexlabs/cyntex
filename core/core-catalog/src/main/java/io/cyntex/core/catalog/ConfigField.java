package io.cyntex.core.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One normalized connection config field for the wizard: its name, type, localized label, whether
 * it is required or secret, an optional default and enum choices, and an optional conditional
 * visibility rule. {@code label} is keyed by the connector's own locale codes (e.g. {@code en_US}).
 */
public record ConfigField(
        String name,
        ConfigType type,
        Map<String, String> label,
        boolean required,
        String defaultValue,
        boolean secret,
        List<EnumOption> options,
        VisibleWhen visibleWhen) {

    public ConfigField {
        label = label == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(label));
        options = options == null ? List.of() : Collections.unmodifiableList(List.copyOf(options));
    }
}
