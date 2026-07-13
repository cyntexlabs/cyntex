package io.cyntex.adapters.pdk;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.sink.SinkConfig;
import io.cyntex.spi.sink.SinkPort;
import io.cyntex.spi.sink.SinkWriter;
import io.tapdata.pdk.apis.functions.connector.target.WriteRecordFunction;

/**
 * The PDK implementation of the write-side sink port: it provisions a connector, refuses it with a
 * code if its declared API level is incompatible, opens the target once, and yields a writer that
 * encodes cyntex envelopes back to PDK record events. Asking a connector for a write capability it
 * does not provide is a caller invariant violation (the DSL validated the connector's capabilities
 * upstream) and crashes bare rather than being laundered into a code.
 */
public final class PdkSinkPort implements SinkPort {

    private final ConnectorProvisioner provisioner;

    public PdkSinkPort(ConnectorProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public SinkWriter open(SinkConfig config) {
        PdkConnector connector = PdkConnector.open(
                config.connectorId(), provisioner.resolve(config.connectorId()), config.settings());
        WriteRecordFunction write;
        try {
            write = requireWriteFunction(connector.functions().getWriteRecordFunction());
        } catch (RuntimeException e) {
            connector.close();
            throw e;
        }
        try {
            connector.underLoader(() -> {
                connector.connector().init(connector.context());
                return null;
            });
        } catch (CyntexException e) {
            connector.stopQuietly();
            connector.close();
            throw e;
        } catch (Throwable t) {
            connector.stopQuietly();
            connector.close();
            throw PdkSinkWriter.writeFailed(connector.connectorId(), t);
        }
        return new PdkSinkWriter(connector, write, config.writeMode(), config.ddl(), config.target());
    }

    private static WriteRecordFunction requireWriteFunction(WriteRecordFunction function) {
        if (function == null) {
            throw new IllegalStateException("connector does not provide a write capability");
        }
        return function;
    }
}
