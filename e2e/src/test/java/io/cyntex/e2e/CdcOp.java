package io.cyntex.e2e;

/** The change operations a cdc step can produce against a seeded table. */
public enum CdcOp {
    INSERT,
    UPDATE,
    DELETE
}
