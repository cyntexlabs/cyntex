package io.cyntex.control.core;

import java.util.List;
import java.util.Map;

/** One normalized tagged table selector in a Source response. */
public record SourceTableView(
        String type,
        String name,
        String pattern,
        String filter,
        List<String> pk,
        Map<String, Object> options) {

    public SourceTableView {
        pk = pk == null ? null : List.copyOf(pk);
        options = SourceRepresentation.copyJsonMap(options, true);
    }
}
