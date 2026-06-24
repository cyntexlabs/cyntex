package io.cyntex.core.catalog;

import java.util.Collections;
import java.util.List;

import io.cyntex.core.model.WriteMode;

/**
 * Whether a connector can act as a table sink and, if so, which write modes it supports.
 * {@code capable} is derived from the {@code write_record} capability; {@code writeSemantics} is
 * read from the connector's DML policies (in {@link WriteMode} declaration order, deterministic).
 */
public record SinkCapability(boolean capable, List<WriteMode> writeSemantics) {

    public SinkCapability {
        writeSemantics = writeSemantics == null
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(writeSemantics));
    }
}
