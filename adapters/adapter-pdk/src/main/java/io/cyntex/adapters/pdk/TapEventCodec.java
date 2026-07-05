package io.cyntex.adapters.pdk;

import java.util.LinkedHashMap;
import java.util.Map;

import io.cyntex.core.event.Envelope;
import io.tapdata.entity.event.TapBaseEvent;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.event.ddl.TapDDLEvent;
import io.tapdata.entity.event.ddl.TapDDLUnknownEvent;
import io.tapdata.entity.event.dml.TapDeleteRecordEvent;
import io.tapdata.entity.event.dml.TapInsertRecordEvent;
import io.tapdata.entity.event.dml.TapUpdateRecordEvent;

/**
 * Projects a PDK {@code TapEvent} to and from the cyntex event envelope — the stable currency every
 * downstream transform sees. The projection rules are a long-lived contract: they are pinned by a
 * golden sample, so changing them is a reviewed change to the golden, never a silent drift.
 *
 * <p>Whether a row is a snapshot read ({@code op=r}) or a cdc insert ({@code op=i}) is not carried on
 * the event — a snapshot row and a cdc insert are the same insert-shaped {@code TapEvent} on the
 * wire; the phase is external truth, known from which function produced the batch. So decode is
 * split by phase: {@link #decodeSnapshotRow} for batch-read output, {@link #decodeChange} for the
 * change stream. A ddl event is projected, never dropped — swallowing it would silently break the
 * schema-evolution chain downstream.
 *
 * <p>Field projection: {@code src} is the event's table id (the logical stream name); {@code ts} is
 * the source reference time, falling back to the event time. Row data maps ({@code before}/
 * {@code after}) pass through as-is. A ddl event's {@code schema} carries the origin ddl when the
 * connector supplied one; precise per-kind ddl translation is deferred, so this stays a coarse,
 * pass-through "track" projection.
 */
public final class TapEventCodec {

    /** The {@code schema}-map key under which a ddl event's origin ddl travels. */
    private static final String DDL_ORIGIN = "origin";

    private TapEventCodec() {
    }

    /**
     * Decodes a change-stream event ({@code i}/{@code u}/{@code d}/{@code ddl}).
     *
     * @throws IllegalArgumentException if the event is not a mapped change type
     */
    public static Envelope decodeChange(TapEvent event) {
        if (event instanceof TapInsertRecordEvent insert) {
            return Envelope.insert(ts(insert), src(insert), insert.getAfter(), null);
        }
        if (event instanceof TapUpdateRecordEvent update) {
            return Envelope.update(ts(update), src(update), update.getBefore(), update.getAfter(), null);
        }
        if (event instanceof TapDeleteRecordEvent delete) {
            return Envelope.delete(ts(delete), src(delete), delete.getBefore(), null);
        }
        if (event instanceof TapDDLEvent ddl) {
            return Envelope.ddl(ts(ddl), src(ddl), ddlSchema(ddl));
        }
        throw new IllegalArgumentException("unmapped change event type: " + event.getClass().getName());
    }

    /**
     * Decodes a snapshot row as {@code op=r}. Batch reads yield insert-shaped rows only.
     *
     * @throws IllegalArgumentException if the event is not insert-shaped
     */
    public static Envelope decodeSnapshotRow(TapEvent event) {
        if (event instanceof TapInsertRecordEvent row) {
            return Envelope.read(ts(row), src(row), row.getAfter(), null);
        }
        throw new IllegalArgumentException(
                "snapshot rows are insert-shaped; got: " + event.getClass().getName());
    }

    /**
     * Encodes an envelope back to a PDK {@code TapEvent}; a snapshot read encodes insert-shaped. The
     * row maps are handed over as fresh mutable copies: the sink value-conversion path mutates them in
     * place, which the envelope's own unmodifiable maps would reject.
     */
    public static TapEvent encode(Envelope env) {
        return switch (env.op()) {
            case INSERT, READ -> TapInsertRecordEvent.create()
                    .table(env.src()).referenceTime(env.ts()).after(mutable(env.after()));
            case UPDATE -> TapUpdateRecordEvent.create()
                    .table(env.src()).referenceTime(env.ts()).before(mutable(env.before())).after(mutable(env.after()));
            case DELETE -> TapDeleteRecordEvent.create()
                    .table(env.src()).referenceTime(env.ts()).before(mutable(env.before()));
            case DDL -> encodeDdl(env);
        };
    }

    /** A fresh mutable copy PDK can write through in place, or {@code null} when the map is absent. */
    private static Map<String, Object> mutable(Map<String, Object> map) {
        return map == null ? null : new LinkedHashMap<>(map);
    }

    private static TapEvent encodeDdl(Envelope env) {
        TapDDLUnknownEvent ddl = new TapDDLUnknownEvent();
        ddl.setTableId(env.src());
        ddl.setReferenceTime(env.ts());
        Object origin = env.schema() == null ? null : env.schema().get(DDL_ORIGIN);
        if (origin != null) {
            ddl.setOriginDDL(origin);
        }
        return ddl;
    }

    private static Map<String, Object> ddlSchema(TapDDLEvent ddl) {
        Object origin = ddl.getOriginDDL();
        return origin == null ? Map.of() : Map.of(DDL_ORIGIN, String.valueOf(origin));
    }

    /** The source reference time, falling back to the event time, or {@code 0} when neither is set. */
    private static long ts(TapBaseEvent event) {
        Long reference = event.getReferenceTime();
        if (reference != null) {
            return reference;
        }
        Long time = event.getTime();
        return time != null ? time : 0L;
    }

    private static String src(TapBaseEvent event) {
        return event.getTableId();
    }
}
