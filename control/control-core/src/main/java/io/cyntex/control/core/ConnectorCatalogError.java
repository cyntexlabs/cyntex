package io.cyntex.control.core;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/** Stable coded failures for the connector catalog read surface. */
public enum ConnectorCatalogError implements CyntexErrorCode {

    NOT_FOUND("connector.not-found", Set.of("connector"));

    private final String code;
    private final Set<String> placeholders;

    ConnectorCatalogError(String code, Set<String> placeholders) {
        this.code = code;
        this.placeholders = placeholders;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> placeholders() {
        return placeholders;
    }
}
