package io.cyntex.control.core;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/** Stable coded failures for Source-specific CRUD operations. */
public enum SourceError implements CyntexErrorCode {

    NOT_FOUND("source.not-found", Set.of("id")),
    ALREADY_EXISTS("source.already-exists", Set.of("id")),
    ID_MISMATCH("source.id-mismatch", Set.of("pathId", "bodyId")),
    PRECONDITION_REQUIRED("source.precondition-required", Set.of("id")),
    VERSION_CONFLICT("source.version-conflict", Set.of("id")),
    IN_USE("source.in-use", Set.of("id", "referrers"));

    private final String code;
    private final Set<String> placeholders;

    SourceError(String code, Set<String> placeholders) {
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
