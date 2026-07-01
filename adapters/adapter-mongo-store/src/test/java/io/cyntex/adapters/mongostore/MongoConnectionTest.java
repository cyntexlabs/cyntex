package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The connection substrate's fast-fail behavior — no Docker required: pointed at a dead port, it
 * reports the store unreachable as a coded diagnostic (not a bare driver exception) within the
 * configured server-selection timeout. The replica-set check and the CAS witness against a real
 * Mongo live in {@code MongoConnectionReplicaSetIT} (Testcontainers).
 */
class MongoConnectionTest {

    @Test
    void unreachableTargetIsReportedAsACodedDiagnostic() {
        MongoConnectionSettings settings = new MongoConnectionSettings(
                "mongodb://localhost:1/cyntex", false, Duration.ofMillis(300));
        try (MongoConnection connection = new MongoConnection(settings)) {
            CyntexException ex = catchThrowableOfType(connection::verify, CyntexException.class);
            assertThat(ex).as("verify() against a dead port raises a coded diagnostic").isNotNull();
            assertThat(ex.code()).isEqualTo(StoreError.UNREACHABLE);
            assertThat(ex.args()).containsKey("target");
        }
    }

    @Test
    void malformedUriIsReportedAsACodedDiagnostic() {
        MongoConnectionSettings settings = new MongoConnectionSettings(
                "not-a-mongodb-uri", false, Duration.ofMillis(300));
        try (MongoConnection connection = new MongoConnection(settings)) {
            CyntexException ex = catchThrowableOfType(connection::verify, CyntexException.class);
            assertThat(ex).as("verify() with a malformed URI raises a coded diagnostic, not a bare IAE").isNotNull();
            assertThat(ex.code()).isEqualTo(StoreError.INVALID_URI);
        }
    }
}
