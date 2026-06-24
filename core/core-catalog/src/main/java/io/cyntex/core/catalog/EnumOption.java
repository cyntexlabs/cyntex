package io.cyntex.core.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One choice of an enum config field: the stored {@code value} and its localized {@code label}
 * (locale code → text), keyed by the connector's own locale codes (e.g. {@code en_US}).
 */
public record EnumOption(String value, Map<String, String> label) {

    public EnumOption {
        label = label == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(label));
    }
}
