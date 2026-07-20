package io.cyntex.spi.store;

/** Outcome of an atomic artifact create, versioned replace, or versioned delete. */
public enum ArtifactMutation {
    CREATED,
    REPLACED,
    DELETED,
    NOT_FOUND,
    ALREADY_EXISTS,
    VERSION_CONFLICT
}
