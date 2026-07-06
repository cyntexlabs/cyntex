package io.cyntex.adapters.pdk;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.event.Envelope;
import io.cyntex.spi.capture.CaptureBatch;
import io.cyntex.spi.capture.CaptureConfig;
import io.cyntex.spi.capture.CaptureListener;
import io.cyntex.spi.capture.CapturePort;
import io.cyntex.spi.capture.ConnectionReport;
import io.cyntex.spi.capture.DiscoveredSchema;
import io.cyntex.spi.capture.FieldSchema;
import io.cyntex.spi.capture.Subscription;
import io.cyntex.spi.capture.TableSchema;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.pdk.apis.consumer.StreamReadConsumer;
import io.tapdata.pdk.apis.functions.connector.source.BatchReadFunction;
import io.tapdata.pdk.apis.functions.connector.source.StreamReadFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The PDK implementation of the read-side capture port: it provisions a connector, refuses it with a
 * code if its declared API level is incompatible, drives its registered read functions through the
 * frozen PDK contract, and projects the PDK events to the cyntex envelope. Connector-side read
 * failures and unprojectable events surface as coded connector-domain exceptions; asking a connector
 * for a read capability it does not provide is a caller invariant violation (the DSL validated the
 * connector's modes upstream) and crashes bare rather than being laundered into a code.
 *
 * <p>Snapshot reads are collected eagerly as a bounded batch. The cdc stream runs on a background
 * thread that delivers each decoded change to the listener; how a stream failure reaches the caller
 * and the backpressure that bounds the stream belong to the runtime that owns stream execution, not
 * to this port.
 */
public final class PdkCapturePort implements CapturePort {

    private static final int BATCH_SIZE = 1000;
    private static final int SAMPLE_SIZE = 10;
    private static final long SHUTDOWN_JOIN_MILLIS = 2000;

    private final ConnectorProvisioner provisioner;

    public PdkCapturePort(ConnectorProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public CaptureBatch snapshot(CaptureConfig config) {
        PdkConnector connector = open(config);
        try {
            // Resolve the read capability before entering the drive: a non-source connector is a caller
            // invariant violation (the modes were validated upstream) and crashes bare here rather than
            // being laundered into a coded capture failure.
            BatchReadFunction batch = requireFunction(connector.functions().getBatchReadFunction());
            List<TapEvent> raw = read(connector, () -> batchRead(connector, config, batch));
            List<Envelope> rows = decodeSnapshot(connector.connectorId(), raw);
            return new PdkCaptureBatch(rows, connector);
        } catch (RuntimeException e) {
            connector.stopQuietly();
            connector.close();
            throw e;
        }
    }

    @Override
    public Subscription cdc(CaptureConfig config, CaptureListener listener) {
        PdkConnector connector = open(config);
        StreamReadFunction stream;
        try {
            stream = requireFunction(connector.functions().getStreamReadFunction());
        } catch (RuntimeException e) {
            connector.close();
            throw e;
        }
        Thread thread = new Thread(() -> streamLoop(connector, config, listener, stream),
                "cyntex-cdc-" + connector.connectorId());
        thread.setDaemon(true);
        thread.start();
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            thread.interrupt();
            connector.stopQuietly();
            joinQuietly(thread);
            connector.close();
        };
    }

    @Override
    public ConnectionReport testConnection(CaptureConfig config) {
        PdkConnector connector = open(config);
        try {
            Probe probe = read(connector, () -> probe(connector, config));
            DiscoveredSchema schema = toDiscoveredSchema(probe.tables());
            List<Envelope> sample = decodeSnapshot(connector.connectorId(), probe.sample());
            return new ConnectionReport(schema, sample);
        } finally {
            connector.stopQuietly();
            connector.close();
        }
    }

    @Override
    public DiscoveredSchema discoverSchema(CaptureConfig config) {
        PdkConnector connector = open(config);
        try {
            List<TapTable> tables = read(connector, () -> discover(connector, config.streams()));
            return toDiscoveredSchema(tables);
        } finally {
            connector.stopQuietly();
            connector.close();
        }
    }

    // ---- drive helpers ---------------------------------------------------------------------------

    private PdkConnector open(CaptureConfig config) {
        return PdkConnector.open(config.connectorId(), provisioner.resolve(config.connectorId()), config.settings());
    }

    /** Inits the connector once, then batch-reads the configured streams (or every discovered stream). */
    private List<TapEvent> batchRead(PdkConnector connector, CaptureConfig config, BatchReadFunction batch) throws Throwable {
        connector.connector().init(connector.context());
        List<String> streams = config.streams().isEmpty()
                ? names(discoverTables(connector, List.of())) : config.streams();
        List<TapEvent> raw = new ArrayList<>();
        for (String stream : streams) {
            batch.batchRead(connector.context(), new TapTable(stream), null, BATCH_SIZE,
                    (events, offset) -> raw.addAll(events));
        }
        return raw;
    }

    /** Inits the connector and discovers the given streams (empty = all). */
    private List<TapTable> discover(PdkConnector connector, List<String> streams) throws Throwable {
        connector.connector().init(connector.context());
        return discoverTables(connector, streams);
    }

    /** Discovers the given streams without initializing — the caller has already inited the connector. */
    private List<TapTable> discoverTables(PdkConnector connector, List<String> streams) throws Throwable {
        List<TapTable> tables = new ArrayList<>();
        connector.connector().discoverSchema(connector.context(), streams, Integer.MAX_VALUE, tables::addAll);
        return tables;
    }

    /** One init, then discover the schema and read a small sample — the connection-test probe. */
    private Probe probe(PdkConnector connector, CaptureConfig config) throws Throwable {
        connector.connector().init(connector.context());
        List<TapTable> tables = new ArrayList<>();
        connector.connector().discoverSchema(connector.context(), config.streams(), Integer.MAX_VALUE, tables::addAll);

        List<TapEvent> sample = new ArrayList<>();
        BatchReadFunction batch = connector.functions().getBatchReadFunction();
        if (batch != null) {
            List<String> streams = config.streams().isEmpty() ? names(tables) : config.streams();
            for (String stream : streams) {
                if (sample.size() >= SAMPLE_SIZE) {
                    break;
                }
                batch.batchRead(connector.context(), new TapTable(stream), null, SAMPLE_SIZE, (events, offset) -> {
                    for (TapEvent event : events) {
                        if (sample.size() < SAMPLE_SIZE) {
                            sample.add(event);
                        }
                    }
                });
            }
        }
        return new Probe(tables, sample);
    }

    private void streamLoop(PdkConnector connector, CaptureConfig config, CaptureListener listener, StreamReadFunction stream) {
        try {
            connector.underLoader(() -> {
                connector.connector().init(connector.context());
                StreamReadConsumer consumer = StreamReadConsumer.create((events, offset) -> {
                    for (TapEvent event : events) {
                        listener.onEvent(TapEventCodec.decodeChange(event));
                    }
                });
                stream.streamRead(connector.context(), config.streams(), null, BATCH_SIZE, consumer);
                return null;
            });
        } catch (Throwable ignore) {
            // The cdc stream runs asynchronously: delivering a stream failure to the caller, and the
            // backpressure that bounds it, belong to the runtime that owns stream execution, not the port.
        }
    }

    /** Runs a read action under the connector loader, mapping a connector-side failure to a code. */
    private static <T> T read(PdkConnector connector, PdkConnector.Action<T> action) {
        try {
            return connector.underLoader(action);
        } catch (CyntexException e) {
            throw e;
        } catch (Throwable t) {
            throw new CyntexException(ConnectorError.CAPTURE_FAILED,
                    Map.of("connector", connector.connectorId(), "detail", detail(t)), t);
        }
    }

    /** Projects raw snapshot rows to envelopes; a codec refusal is a projection failure, not a read failure. */
    private static List<Envelope> decodeSnapshot(String connectorId, List<TapEvent> raw) {
        List<Envelope> rows = new ArrayList<>(raw.size());
        try {
            for (TapEvent event : raw) {
                rows.add(TapEventCodec.decodeSnapshotRow(event));
            }
        } catch (RuntimeException e) {
            throw new CyntexException(ConnectorError.PROJECTION_FAILED,
                    Map.of("connector", connectorId, "detail", detail(e)), e);
        }
        return rows;
    }

    private static DiscoveredSchema toDiscoveredSchema(List<TapTable> tables) {
        List<TableSchema> mapped = new ArrayList<>(tables.size());
        for (TapTable table : tables) {
            List<FieldSchema> fields = new ArrayList<>();
            if (table.getNameFieldMap() != null) {
                table.getNameFieldMap().forEach((name, field) -> fields.add(new FieldSchema(name, field.getDataType())));
            }
            mapped.add(new TableSchema(table.getId(), fields));
        }
        return new DiscoveredSchema(mapped);
    }

    private static List<String> names(List<TapTable> tables) {
        List<String> names = new ArrayList<>(tables.size());
        for (TapTable table : tables) {
            names.add(table.getId());
        }
        return names;
    }

    private static <T> T requireFunction(T function) {
        if (function == null) {
            throw new IllegalStateException("connector does not provide the requested read capability");
        }
        return function;
    }

    /** Waits a bounded time for the stream thread to exit before its loader is closed. */
    private static void joinQuietly(Thread thread) {
        try {
            thread.join(SHUTDOWN_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String detail(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    /** The connection-test probe result: the discovered schema tables and a small raw sample. */
    private record Probe(List<TapTable> tables, List<TapEvent> sample) {
    }
}
