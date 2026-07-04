package io.cyntex.core.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The standard event envelope: one change event as every transform sees it, the common currency of
 * the capture, transform and sink ports. An immutable value.
 *
 * <p>Six slots — {@code op} (the change kind), {@code ts} (event time as epoch milliseconds),
 * {@code src} (the logical stream name the event came from), and three data maps {@code before} /
 * {@code after} / {@code schema}. Which data maps are present follows the op: insert and read carry
 * {@code after}, delete carries {@code before}, update carries both, ddl carries {@code schema} and
 * neither row. An absent map is {@code null}; the {@link #insert} / {@link #update} / {@link #delete}
 * / {@link #read} / {@link #ddl} factory methods encode the per-op shape by construction.
 *
 * <p>The data maps are held as shallow-unmodifiable defensive copies: the map itself cannot be
 * mutated and a later mutation of the caller's map does not leak in, but nested values are shared.
 */
public record Envelope(
        Op op,
        long ts,
        String src,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> schema) {

    public Envelope {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(src, "src");
        before = copyOrNull(before);
        after = copyOrNull(after);
        schema = copyOrNull(schema);
    }

    private static Map<String, Object> copyOrNull(Map<String, Object> map) {
        return map == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** An insert of a new row: {@code after} present, no {@code before}. */
    public static Envelope insert(long ts, String src, Map<String, Object> after, Map<String, Object> schema) {
        return new Envelope(Op.INSERT, ts, src, null, after, schema);
    }

    /** An update of an existing row: both {@code before} and {@code after} present. */
    public static Envelope update(long ts, String src, Map<String, Object> before, Map<String, Object> after, Map<String, Object> schema) {
        return new Envelope(Op.UPDATE, ts, src, before, after, schema);
    }

    /** A delete of a row: {@code before} present, no {@code after}. */
    public static Envelope delete(long ts, String src, Map<String, Object> before, Map<String, Object> schema) {
        return new Envelope(Op.DELETE, ts, src, before, null, schema);
    }

    /** A snapshot batch read of a full row: {@code after} present, no {@code before}. */
    public static Envelope read(long ts, String src, Map<String, Object> after, Map<String, Object> schema) {
        return new Envelope(Op.READ, ts, src, null, after, schema);
    }

    /** A schema change carried by {@code schema}: a non-row event, neither {@code before} nor {@code after}. */
    public static Envelope ddl(long ts, String src, Map<String, Object> schema) {
        return new Envelope(Op.DDL, ts, src, null, null, schema);
    }
}
