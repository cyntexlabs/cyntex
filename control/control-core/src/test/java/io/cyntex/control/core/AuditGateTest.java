package io.cyntex.control.core;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.AuditRecord;
import io.cyntex.spi.store.AuditStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class AuditGateTest {

    private static final Instant FIXED = Instant.parse("2026-07-07T10:15:30Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

    /** A store that captures every record it is asked to write. */
    private static final class RecordingAuditStore implements AuditStore {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }
    }

    /** A store that always fails, standing in for an unavailable audit backend. */
    private static final class FailingAuditStore implements AuditStore {
        @Override
        public void record(AuditRecord record) {
            throw new IllegalStateException("audit backend down");
        }
    }

    @Test
    void auditedOperationIsRefusedWhenTheAuditRecordCannotBeWritten() {
        AuditGate gate = new AuditGate(new FailingAuditStore(), FIXED_CLOCK);
        AtomicBoolean ran = new AtomicBoolean(false);
        AuditContext ctx = new AuditContext("alice", "orders-source");

        CyntexException thrown = catchThrowableOfType(
                () -> gate.dispatch(ControlOperations.ARTIFACT_APPLY, ctx, () -> {
                    ran.set(true);
                    return "done";
                }),
                CyntexException.class);

        assertThat(thrown).as("audit-write failure surfaces as a coded control error").isNotNull();
        assertThat(thrown.code()).isEqualTo(ControlError.AUDIT_BLOCKED);
        assertThat(thrown.args()).containsEntry("op", "artifact.apply");
        assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(ran)
                .as("the action must not run when its mandatory audit record could not be written")
                .isFalse();
    }

    @Test
    void auditedOperationRecordsAnEntryThenRunsTheAction() {
        RecordingAuditStore store = new RecordingAuditStore();
        AuditGate gate = new AuditGate(store, FIXED_CLOCK);

        String result = gate.dispatch(
                ControlOperations.ARTIFACT_APPLY, new AuditContext("alice", "orders-source"), () -> "applied");

        assertThat(result).isEqualTo("applied");
        assertThat(store.records).hasSize(1);
        AuditRecord record = store.records.get(0);
        assertThat(record.operationId()).isEqualTo("artifact.apply");
        assertThat(record.principal()).isEqualTo("alice");
        assertThat(record.resourceId()).isEqualTo("orders-source");
        assertThat(record.timestamp()).isEqualTo(FIXED);
    }

    @Test
    void aNonAuditedOperationRunsWithoutRecordingAnEntry() {
        RecordingAuditStore store = new RecordingAuditStore();
        AuditGate gate = new AuditGate(store, FIXED_CLOCK);
        AtomicBoolean ran = new AtomicBoolean(false);

        gate.dispatch(ControlOperations.ARTIFACT_GET, new AuditContext("alice", "orders-source"), () -> {
            ran.set(true);
            return null;
        });

        assertThat(ran).isTrue();
        assertThat(store.records)
                .as("a read operation carries no audit flag and leaves no audit record")
                .isEmpty();
    }

    @Test
    void auditRecordSurvivesAndTheActionExceptionPropagatesWhenTheActionThrows() {
        RecordingAuditStore store = new RecordingAuditStore();
        AuditGate gate = new AuditGate(store, FIXED_CLOCK);
        RuntimeException boom = new IllegalArgumentException("action failed");

        Throwable thrown = catchThrowable(() -> gate.dispatch(
                ControlOperations.ARTIFACT_APPLY, new AuditContext("alice", "orders-source"), () -> {
                    throw boom;
                }));

        // Audit-first: the record captures the attempt and precedes the effect, so a failed action still
        // leaves its audit record — and the action's own error surfaces unchanged, never laundered into
        // the audit-blocked code, which means only that the record itself could not be written.
        assertThat(thrown).isSameAs(boom);
        assertThat(store.records).hasSize(1);
    }

    @Test
    void theAuditRecordIsWrittenBeforeTheActionRuns() {
        List<String> events = new ArrayList<>();
        AuditStore store = record -> events.add("audit");
        AuditGate gate = new AuditGate(store, FIXED_CLOCK);

        gate.dispatch(ControlOperations.ARTIFACT_APPLY, new AuditContext("alice", "orders-source"), () -> {
            events.add("action");
            return null;
        });

        assertThat(events).containsExactly("audit", "action");
    }
}
