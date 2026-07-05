package io.cyntex.adapters.pdk;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.event.Envelope;
import io.cyntex.spi.sink.DdlPolicy;
import io.cyntex.spi.sink.SinkConfig;
import io.cyntex.spi.sink.SinkWriter;
import io.cyntex.spi.sink.WriteMode;
import io.cyntex.spi.sink.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write-side PDK bridge: {@link PdkSinkPort} encoding cyntex envelopes back to PDK record events
 * and driving a connector's registered write function, with connector-side failures surfaced as coded
 * connector-domain exceptions and the write mode / ddl policy enforced. Synthetic connectors compiled
 * at test time stand in for a real connector jar and the PDK runtime the host does not yet provide.
 */
class PdkSinkPortTest {

    private static ConnectorProvisioner provisioner(Path jar, String className) {
        ConnectorRef ref = new ConnectorRef(List.of(jar), className, "2.0.8", null);
        return connectorId -> ref;
    }

    private static SinkConfig config(WriteMode mode, DdlPolicy ddl) {
        return new SinkConfig("demo", Map.of(), mode, ddl);
    }

    private static WriteResult await(SinkWriter writer, List<Envelope> records) throws Exception {
        return writer.write(records).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static Envelope insert(int id) {
        return Envelope.insert(1L, "t1", Map.of("id", id), null);
    }

    @Test
    void writeDeliversRowsAndReportsTheAcceptedCount(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.countingSink(dir);
        PdkSinkPort port = new PdkSinkPort(provisioner(jar, "synthetic.CountingSink"));
        try (SinkWriter writer = port.open(config(WriteMode.UPSERT, DdlPolicy.APPLY))) {
            assertThat(await(writer, List.of(insert(1), insert(2))).written()).isEqualTo(2);
        }
    }

    @Test
    void writeAccumulatesCountsAcrossMultipleConnectorFlushes(@TempDir Path dir) throws Exception {
        // A connector may report a batch in several flushes, one consumer callback each; the writer must
        // sum the accepted counts, not report only the last flush's.
        Path jar = Synthetic.multiFlushSink(dir);
        PdkSinkPort port = new PdkSinkPort(provisioner(jar, "synthetic.MultiFlush"));
        try (SinkWriter writer = port.open(config(WriteMode.UPSERT, DdlPolicy.APPLY))) {
            assertThat(await(writer, List.of(insert(1), insert(2))).written()).isEqualTo(2);
        }
    }

    @Test
    void aConnectorThatThrowsWhileWritingIsACodedWriteFailure(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.throwingWriteSink(dir);
        PdkSinkPort port = new PdkSinkPort(provisioner(jar, "synthetic.ThrowingWrite"));
        try (SinkWriter writer = port.open(config(WriteMode.UPSERT, DdlPolicy.APPLY))) {
            assertThatThrownBy(() -> await(writer, List.of(insert(1))))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CyntexException.class)
                    .satisfies(e -> assertThat(((CyntexException) e.getCause()).code())
                            .isEqualTo(ConnectorError.WRITE_FAILED));
        }
    }

    @Test
    void appendModeReforgesUpdatesAndDeletesToInserts(@TempDir Path dir) throws Exception {
        // The inserts-only sink rejects any non-insert event; that the append-mode write succeeds proves
        // the update and delete were reforged into inserts before reaching the connector.
        Path jar = Synthetic.insertsOnlySink(dir);
        PdkSinkPort port = new PdkSinkPort(provisioner(jar, "synthetic.InsertsOnly"));
        Envelope update = Envelope.update(1L, "t1", Map.of("id", 1), Map.of("id", 1, "v", 2), null);
        Envelope delete = Envelope.delete(1L, "t1", Map.of("id", 1), null);
        try (SinkWriter writer = port.open(config(WriteMode.APPEND, DdlPolicy.IGNORE))) {
            assertThat(await(writer, List.of(update, delete)).written()).isEqualTo(2);
        }
    }

    @Test
    void upsertModePassesRowEventsThroughUnchanged(@TempDir Path dir) throws Exception {
        // In upsert mode an update stays an update; the inserts-only sink rejects it, proving no reforge.
        Path jar = Synthetic.insertsOnlySink(dir);
        PdkSinkPort port = new PdkSinkPort(provisioner(jar, "synthetic.InsertsOnly"));
        Envelope update = Envelope.update(1L, "t1", Map.of("id", 1), Map.of("id", 1, "v", 2), null);
        try (SinkWriter writer = port.open(config(WriteMode.UPSERT, DdlPolicy.IGNORE))) {
            assertThatThrownBy(() -> await(writer, List.of(update)))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CyntexException.class)
                    .satisfies(e -> assertThat(((CyntexException) e.getCause()).code())
                            .isEqualTo(ConnectorError.WRITE_FAILED));
        }
    }

    @Test
    void aSchemaChangeUnderFailPolicyIsACodedWriteFailure(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.countingSink(dir);
        PdkSinkPort port = new PdkSinkPort(provisioner(jar, "synthetic.CountingSink"));
        try (SinkWriter writer = port.open(config(WriteMode.UPSERT, DdlPolicy.FAIL))) {
            assertThatThrownBy(() -> await(writer, List.of(Envelope.ddl(1L, "t1", Map.of()))))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CyntexException.class)
                    .satisfies(e -> assertThat(((CyntexException) e.getCause()).code())
                            .isEqualTo(ConnectorError.WRITE_FAILED));
        }
    }

    @Test
    void aSchemaChangeUnderIgnorePolicyIsSkippedAndRowsStillWrite(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.countingSink(dir);
        PdkSinkPort port = new PdkSinkPort(provisioner(jar, "synthetic.CountingSink"));
        try (SinkWriter writer = port.open(config(WriteMode.UPSERT, DdlPolicy.IGNORE))) {
            // The batch carries one row and one schema change; only the row reaches the connector.
            assertThat(await(writer, List.of(insert(1), Envelope.ddl(1L, "t1", Map.of()))).written()).isEqualTo(1);
        }
    }
}
