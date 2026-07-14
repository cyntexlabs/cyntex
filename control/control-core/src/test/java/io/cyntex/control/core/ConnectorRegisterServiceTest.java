package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.AuditRecord;
import io.cyntex.spi.store.AuditStore;
import io.cyntex.spi.store.ConnectorRegistrar;
import io.cyntex.spi.store.ConnectorRegistration;
import io.cyntex.spi.store.ContentHash;
import io.cyntex.spi.store.RegistrationOutcome;
import io.cyntex.spi.store.RegistrationSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConnectorRegisterServiceTest {

    private static final byte[] ARTIFACT = "orders-connector-bytes".getBytes(StandardCharsets.UTF_8);
    private static final ConnectorRegistration REGISTRATION =
            new ConnectorRegistration("orders", ContentHash.of(ARTIFACT), "1.3.5", RegistrationSource.REGISTER);

    @Test
    void registersTheArtifactUnderTheRegisterSourceAndReturnsItsReport() {
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<RegistrationSource> source = new AtomicReference<>();
        ConnectorRegistrar registrar = (bytes, src) -> {
            received.set(bytes);
            source.set(src);
            return new RegistrationOutcome(REGISTRATION, true);
        };
        ConnectorRegisterService service = new ConnectorRegisterService(registrar, gate(new RecordingAuditStore()));

        ConnectorRegistrationReport report = service.register(ARTIFACT, "alice");

        // The report is the control-ring projection of the store outcome.
        assertThat(report.connectorId()).isEqualTo("orders");
        assertThat(report.contentHash()).isEqualTo(ContentHash.of(ARTIFACT));
        assertThat(report.pdkApiVersion()).isEqualTo("1.3.5");
        assertThat(report.newlyRegistered()).isTrue();
        // The registrar was driven with the given bytes under the explicit runtime register source.
        assertThat(received.get()).isEqualTo(ARTIFACT);
        assertThat(source.get()).isEqualTo(RegistrationSource.REGISTER);
    }

    @Test
    void reportsAnAlreadyRegisteredArtifactAsNotNewlyRegistered() {
        ConnectorRegistrar registrar = (bytes, src) -> new RegistrationOutcome(REGISTRATION, false);
        ConnectorRegisterService service = new ConnectorRegisterService(registrar, gate(new RecordingAuditStore()));

        ConnectorRegistrationReport report = service.register(ARTIFACT, "alice");

        assertThat(report.newlyRegistered()).isFalse();
        assertThat(report.connectorId()).isEqualTo("orders");
    }

    @Test
    void auditsTheRegistrationUnderConnectorRegisterAgainstTheArtifactContentHash() {
        RecordingAuditStore auditStore = new RecordingAuditStore();
        ConnectorRegistrar registrar = (bytes, src) -> new RegistrationOutcome(REGISTRATION, true);
        ConnectorRegisterService service = new ConnectorRegisterService(registrar, gate(auditStore));

        service.register(ARTIFACT, "alice");

        assertThat(auditStore.records).hasSize(1);
        AuditRecord record = auditStore.records.get(0);
        assertThat(record.operationId()).isEqualTo("connector.register");
        assertThat(record.principal()).isEqualTo("alice");
        // The audited resource is the artifact, keyed by its content hash — known without classloading.
        assertThat(record.resourceId()).isEqualTo(ContentHash.of(ARTIFACT));
    }

    @Test
    void refusesTheRegistrationWhenTheAuditWriteFailsWithoutRegistering() {
        AtomicReference<byte[]> received = new AtomicReference<>();
        ConnectorRegistrar registrar = (bytes, src) -> {
            received.set(bytes);
            return new RegistrationOutcome(REGISTRATION, true);
        };
        AuditStore failing = record -> {
            throw new RuntimeException("audit log unavailable");
        };
        ConnectorRegisterService service = new ConnectorRegisterService(registrar, gate(failing));

        assertThatThrownBy(() -> service.register(ARTIFACT, "alice")).isInstanceOf(CyntexException.class);
        // No audit, no execute: the registrar was never driven.
        assertThat(received.get()).isNull();
    }

    @Test
    void requiresItsCollaborators() {
        ConnectorRegistrar registrar = (bytes, src) -> new RegistrationOutcome(REGISTRATION, true);
        AuditGate gate = gate(new RecordingAuditStore());
        assertThatThrownBy(() -> new ConnectorRegisterService(null, gate)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConnectorRegisterService(registrar, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConnectorRegisterService(registrar, gate).register(null, "alice"))
                .isInstanceOf(NullPointerException.class);
    }

    private static AuditGate gate(AuditStore auditStore) {
        return new AuditGate(auditStore, Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC));
    }

    private static final class RecordingAuditStore implements AuditStore {
        private final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }
    }
}
