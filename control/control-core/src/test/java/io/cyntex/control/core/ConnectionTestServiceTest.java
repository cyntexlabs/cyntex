package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.common.CyntexException;
import io.cyntex.runtime.probe.ConnectionProbe;
import io.cyntex.spi.store.AuditRecord;
import io.cyntex.spi.store.AuditStore;
import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.ConnectionTestResult;
import io.cyntex.spi.store.ConnectionTestResult.Outcome;
import io.cyntex.spi.store.ConnectionTestResultStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConnectionTestServiceTest {

    private static final ConnectionConfig CONFIG =
            new ConnectionConfig("conn-orders", "mongodb", Map.of("uri", "mongodb://localhost"));
    private static final ConnectionTestResult RESULT =
            new ConnectionTestResult("conn-orders", "mongodb", Outcome.PASSED, List.of(), 1_700_000_000_000L);

    @Test
    void probesSavesTheLatestResultAndReturnsIt() {
        AtomicReference<ConnectionConfig> probed = new AtomicReference<>();
        ConnectionProbe probe = config -> {
            probed.set(config);
            return RESULT;
        };
        RecordingResultStore store = new RecordingResultStore();
        RecordingAuditStore auditStore = new RecordingAuditStore();
        ConnectionTestService service = new ConnectionTestService(probe, store, gate(auditStore));

        ConnectionTestReport returned = service.test(CONFIG.id(), CONFIG.connectorId(), CONFIG.settings(), "alice");

        // The returned report is the control-ring projection of the probe's result.
        assertThat(returned.connectionId()).isEqualTo("conn-orders");
        assertThat(returned.connectorId()).isEqualTo("mongodb");
        assertThat(returned.outcome()).isEqualTo(ConnectionTestReport.Outcome.PASSED);
        // The probe was driven with a config carrying exactly the given connection.
        assertThat(probed.get()).isEqualTo(CONFIG);
        // The storage-port result is what gets persisted, unchanged.
        assertThat(store.saved).isSameAs(RESULT);
    }

    @Test
    void auditsTheTestUnderConnectionTestAgainstTheConnectionId() {
        RecordingAuditStore auditStore = new RecordingAuditStore();
        ConnectionTestService service =
                new ConnectionTestService(config -> RESULT, new RecordingResultStore(), gate(auditStore));

        service.test(CONFIG.id(), CONFIG.connectorId(), CONFIG.settings(), "alice");

        assertThat(auditStore.records).hasSize(1);
        AuditRecord record = auditStore.records.get(0);
        assertThat(record.operationId()).isEqualTo("connection.test");
        assertThat(record.principal()).isEqualTo("alice");
        assertThat(record.resourceId()).isEqualTo("conn-orders");
    }

    @Test
    void refusesTheTestWhenTheAuditWriteFailsWithoutProbingOrSaving() {
        AtomicBoolean probed = new AtomicBoolean(false);
        ConnectionProbe probe = config -> {
            probed.set(true);
            return RESULT;
        };
        RecordingResultStore store = new RecordingResultStore();
        AuditStore failing = record -> {
            throw new RuntimeException("audit log unavailable");
        };
        ConnectionTestService service = new ConnectionTestService(probe, store, gate(failing));

        assertThatThrownBy(() -> service.test(CONFIG.id(), CONFIG.connectorId(), CONFIG.settings(), "alice"))
                .isInstanceOf(CyntexException.class);
        assertThat(probed).isFalse();
        assertThat(store.saved).isNull();
    }

    @Test
    void requiresItsCollaborators() {
        ConnectionProbe probe = config -> RESULT;
        RecordingResultStore store = new RecordingResultStore();
        AuditGate auditGate = gate(new RecordingAuditStore());
        assertThatThrownBy(() -> new ConnectionTestService(null, store, auditGate))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConnectionTestService(probe, null, auditGate))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConnectionTestService(probe, store, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static AuditGate gate(AuditStore auditStore) {
        return new AuditGate(auditStore, Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC));
    }

    private static final class RecordingResultStore implements ConnectionTestResultStore {
        private ConnectionTestResult saved;

        @Override
        public void save(ConnectionTestResult result) {
            this.saved = result;
        }

        @Override
        public Optional<ConnectionTestResult> find(String connectionId) {
            return Optional.ofNullable(saved);
        }
    }

    private static final class RecordingAuditStore implements AuditStore {
        private final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }
    }
}
