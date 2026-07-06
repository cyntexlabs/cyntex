package io.cyntex.adapters.pdk;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.event.Envelope;
import io.cyntex.core.event.Op;
import io.cyntex.spi.sink.DdlPolicy;
import io.cyntex.spi.sink.SinkWriter;
import io.cyntex.spi.sink.WriteMode;
import io.cyntex.spi.sink.WriteResult;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.pdk.apis.entity.WriteListResult;
import io.tapdata.pdk.apis.functions.connector.target.WriteRecordFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A write session over one sink connector, opened once and held across batches. Each write encodes the
 * row envelopes back to PDK record events and drives the connector's write function; the returned
 * stage completes with the count the target accepted, or exceptionally with a coded write failure.
 *
 * <p>The write intent is enforced here: append mode reforges updates and deletes into inserts (an
 * append-only stream), while upsert mode passes them through keyed. A schema-change event is handled by
 * the ddl policy — {@code fail} rejects the batch with a code, {@code ignore} drops it; applying a
 * schema change to the target is a later slice, so {@code apply} drops it for now.
 *
 * <p>Writes run off the caller's thread so the call does not block; how many writes are in flight and
 * the backpressure that bounds them belong to the runtime, not this writer.
 */
final class PdkSinkWriter implements SinkWriter {

    private final PdkConnector connector;
    private final WriteRecordFunction write;
    private final WriteMode mode;
    private final DdlPolicy ddl;
    private boolean closed;

    PdkSinkWriter(PdkConnector connector, WriteRecordFunction write, WriteMode mode, DdlPolicy ddl) {
        this.connector = connector;
        this.write = write;
        this.mode = mode;
        this.ddl = ddl;
    }

    @Override
    public CompletionStage<WriteResult> write(List<Envelope> records) {
        return CompletableFuture.supplyAsync(() -> deliver(records));
    }

    private WriteResult deliver(List<Envelope> records) {
        List<TapRecordEvent> rows = new ArrayList<>(records.size());
        for (Envelope env : records) {
            if (env.op() == Op.DDL) {
                if (ddl == DdlPolicy.FAIL) {
                    throw writeFailed(connector.connectorId(),
                            new IllegalStateException("schema change reached a sink whose ddl policy is fail"));
                }
                // ignore: drop the schema change. apply: applying it to the target is a later slice, so drop it too.
                continue;
            }
            rows.add(encode(mode == WriteMode.APPEND ? asInsert(env) : env));
        }
        if (rows.isEmpty()) {
            return new WriteResult(0);
        }
        try {
            return connector.underLoader(() -> {
                long[] accepted = {0};
                // A connector may report the batch in several flushes, one callback each; accumulate.
                write.writeRecord(connector.context(), rows, new TapTable(rows.get(0).getTableId()),
                        result -> accepted[0] += accepted(result));
                return new WriteResult(accepted[0]);
            });
        } catch (CyntexException e) {
            throw e;
        } catch (Throwable t) {
            throw writeFailed(connector.connectorId(), t);
        }
    }

    private static long accepted(WriteListResult<TapRecordEvent> result) {
        return result.getInsertedCount() + result.getModifiedCount() + result.getRemovedCount();
    }

    private static TapRecordEvent encode(Envelope env) {
        return (TapRecordEvent) TapEventCodec.encode(env);
    }

    /** Reforge a row envelope into an insert (append mode): the row's payload becomes an inserted row. */
    private static Envelope asInsert(Envelope env) {
        Map<String, Object> row = env.after() != null ? env.after() : env.before();
        return Envelope.insert(env.ts(), env.src(), row, env.schema());
    }

    static CyntexException writeFailed(String connectorId, Throwable cause) {
        return new CyntexException(ConnectorError.WRITE_FAILED,
                Map.of("connector", connectorId, "detail", detail(cause)), cause);
    }

    private static String detail(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        connector.stopQuietly();
        connector.close();
    }
}
