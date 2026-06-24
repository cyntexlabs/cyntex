package io.cyntex.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One embedded child of a nest tree (ADR-0016 §5.1). {@code from} names an alias of the
 * step's {@code from:} map; {@code on} maps child join fields to parent fields.
 */
@Doc("One embedded child of a nest tree, joined onto its parent and placed at a target path.")
public record Embed(
        @Doc(value = "Alias of the nest step's from map that supplies this child's rows.", required = true)
        String from,
        @Doc(value = "Maps this child's join fields to the parent fields they match.", required = true)
        Map<String, String> on,
        @Doc(value = "How the matched child rows are shaped under the parent: a single object or an array.", required = true)
        EmbedAs as,
        @Doc(value = "Target field path under the parent where the embedded child is placed.", required = true)
        String path,
        @Doc(value = "Fields that uniquely identify an element within an embedded array.",
                key = "arrayKey")
        List<String> arrayKey,
        @Doc(value = "When true, updates to the child rows are not propagated into the parent.",
                key = "ignoreUpdates")
        Boolean ignoreUpdates,
        @Doc(value = "When true, changes to the join key are tracked so embedded data is moved accordingly.",
                key = "trackJoinKeyChanges")
        Boolean trackJoinKeyChanges,
        @Doc("Further children embedded beneath this one, forming a nested tree.")
        List<Embed> embed) {

    public Embed {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(as, "as");
        Objects.requireNonNull(path, "path");
        on = Collections.unmodifiableMap(new LinkedHashMap<>(on));
        arrayKey = arrayKey == null ? null : List.copyOf(arrayKey);
        embed = embed == null ? null : List.copyOf(embed);
    }
}
