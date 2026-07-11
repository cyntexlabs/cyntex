package io.cyntex.control.core;

import io.cyntex.runtime.probe.ConnectionProbe;
import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.ConnectionTestResult;
import io.cyntex.spi.store.ConnectionTestResultStore;
import java.util.Objects;

/**
 * The control plane's connection-test verb: it drives the target connection through the runtime probe —
 * the one synchronous control-to-runtime seam — records the normalized result as the connection's latest
 * test result, and returns it. The whole operation runs under the audit gate: a connection test is an
 * audited write, so an audit record is written before the probe runs and the operation is refused if that
 * write fails (no audit, no execute).
 *
 * <p>The probe is injected: the runtime supplies the seam implementation, and when the control and runtime
 * roles split the same call travels across instances. The result store keeps only the latest result per
 * connection; who tested when is answered by the audit log, not the result store, so the two never
 * overlap. A probe that cannot run the test at all throws a coded connector-domain failure, which
 * propagates out of here uncaught — no result is stored for a test that never produced one.
 */
public final class ConnectionTestService {

    private final ConnectionProbe probe;
    private final ConnectionTestResultStore resultStore;
    private final AuditGate auditGate;

    public ConnectionTestService(
            ConnectionProbe probe, ConnectionTestResultStore resultStore, AuditGate auditGate) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.resultStore = Objects.requireNonNull(resultStore, "resultStore");
        this.auditGate = Objects.requireNonNull(auditGate, "auditGate");
    }

    /**
     * Tests {@code config}'s connection through the runtime probe under the audit gate, saves the result as
     * the connection's latest, and returns it. {@code principal} is the authenticated subject the audit
     * record is attributed to; the audited resource is the connection's own id.
     */
    public ConnectionTestResult test(ConnectionConfig config, String principal) {
        Objects.requireNonNull(config, "config");
        return auditGate.dispatch(
                ControlOperations.CONNECTION_TEST,
                new AuditContext(principal, config.id()),
                () -> {
                    ConnectionTestResult result = probe.probe(config);
                    resultStore.save(result);
                    return result;
                });
    }
}
