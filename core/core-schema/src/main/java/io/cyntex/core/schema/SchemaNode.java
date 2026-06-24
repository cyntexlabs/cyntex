package io.cyntex.core.schema;

import java.util.List;

/**
 * One resolved position in the {@code cyntex/v1} grammar tree, as seen by {@code explain} and
 * field-path completion. {@code path} is the dotted address ({@code ""} for the root); {@code type}
 * is a display kind ({@code object} / {@code string} / {@code array} / {@code enum} / ...);
 * {@code children} are the navigable field names beneath this node (the element fields for an array);
 * {@code enumValues} is populated only for an enum node; {@code required} lists the required child
 * names and {@code isRequired} says whether this node itself is required in its parent.
 */
public record SchemaNode(
        String path,
        String description,
        String type,
        boolean isRequired,
        List<String> required,
        List<String> children,
        List<EnumValue> enumValues) {

    public SchemaNode {
        required = required == null ? List.of() : List.copyOf(required);
        children = children == null ? List.of() : List.copyOf(children);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    /** One allowed value of an enum node, with its documentation. */
    public record EnumValue(String value, String description) {
    }
}
