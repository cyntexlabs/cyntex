package io.cyntex.core.event;

/**
 * The change kind carried by an {@link Envelope}. A closed set of five: the three row mutations, a
 * snapshot read, and a schema change. Each constant has a stable wire symbol ({@code i} / {@code u}
 * / {@code d} / {@code r} / {@code ddl}); the symbol string, never the enum object, is what crosses
 * a serialization boundary.
 */
public enum Op {

    /** Insert of a new row: {@code after} present, {@code before} absent. */
    INSERT("i"),

    /** Update of an existing row: both {@code before} and {@code after} present. */
    UPDATE("u"),

    /** Delete of a row: {@code before} present, {@code after} absent. */
    DELETE("d"),

    /** Snapshot batch read of a full row: {@code after} present, {@code before} absent. */
    READ("r"),

    /** Schema change carried by {@code schema}: a non-row event, neither {@code before} nor {@code after}. */
    DDL("ddl");

    private final String symbol;

    Op(String symbol) {
        this.symbol = symbol;
    }

    /** The stable wire symbol for this op — what crosses a serialization boundary. */
    public String symbol() {
        return symbol;
    }

    /**
     * The op for a wire symbol.
     *
     * @throws IllegalArgumentException if no op has this symbol. Callers pass a symbol already
     *     validated against untrusted input at their own boundary, so an unrecognized symbol here
     *     is a programmer error, not a diagnosable runtime condition.
     */
    public static Op fromSymbol(String symbol) {
        for (Op op : values()) {
            if (op.symbol.equals(symbol)) {
                return op;
            }
        }
        throw new IllegalArgumentException("unknown envelope op symbol: " + symbol);
    }
}
