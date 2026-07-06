package io.cyntex.adapters.mongostore;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import io.cyntex.core.common.CyntexException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Path;
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
                "mongodb://localhost:1/cyntex", true, null, Duration.ofMillis(300));
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
                "not-a-mongodb-uri", true, null, Duration.ofMillis(300));
        try (MongoConnection connection = new MongoConnection(settings)) {
            CyntexException ex = catchThrowableOfType(connection::verify, CyntexException.class);
            assertThat(ex).as("verify() with a malformed URI raises a coded diagnostic, not a bare IAE").isNotNull();
            assertThat(ex.code()).isEqualTo(StoreError.INVALID_URI);
        }
    }

    @Test
    void plaintextUriWithoutTheInsecureDowngradeIsRefusedUpFront() {
        // ssl=false in the URI turns TLS off. TLS to the store is mandatory, so without the explicit
        // insecure downgrade this is refused before any connection attempt (a dead port would report
        // unreachable if we reached it — a TLS_REQUIRED here proves the guard runs first).
        MongoConnectionSettings settings = new MongoConnectionSettings(
                "mongodb://localhost:1/cyntex?ssl=false", false, null, Duration.ofMillis(300));
        try (MongoConnection connection = new MongoConnection(settings)) {
            CyntexException ex = catchThrowableOfType(connection::verify, CyntexException.class);
            assertThat(ex).as("plaintext URI without the insecure downgrade is refused").isNotNull();
            assertThat(ex.code()).isEqualTo(StoreError.TLS_REQUIRED);
            assertThat(ex.args()).containsKey("target");
        }
    }

    @Test
    void explicitInsecureDowngradeIsNotRefusedAndReachesTheConnection() {
        // With the explicit downgrade the same plaintext URI is permitted: verify() gets past the
        // TLS guard and actually attempts the connection, so a dead port reports unreachable.
        MongoConnectionSettings settings = new MongoConnectionSettings(
                "mongodb://localhost:1/cyntex?ssl=false", true, null, Duration.ofMillis(300));
        try (MongoConnection connection = new MongoConnection(settings)) {
            CyntexException ex = catchThrowableOfType(connection::verify, CyntexException.class);
            assertThat(ex).as("the explicit downgrade permits plaintext, reaching the connection").isNotNull();
            assertThat(ex.code()).isEqualTo(StoreError.UNREACHABLE);
        }
    }

    @Test
    void tlsIsEnabledByDefaultWhenTheUriDoesNotSpecifyIt() {
        MongoClientSettings clientSettings = clientSettingsFor("mongodb://localhost:27017/cyntex", false);
        assertThat(clientSettings.getSslSettings().isEnabled())
                .as("an unspecified URI connects over TLS by default").isTrue();
    }

    @Test
    void aTlsRequestInTheUriIsHonoredEvenWithTheInsecureDowngrade() {
        MongoClientSettings clientSettings = clientSettingsFor("mongodb://localhost:27017/cyntex?ssl=true", true);
        assertThat(clientSettings.getSslSettings().isEnabled())
                .as("a TLS request carried in the URI is never turned off").isTrue();
    }

    @Test
    void theInsecureDowngradeConnectsInPlaintext() {
        MongoClientSettings clientSettings = clientSettingsFor("mongodb://localhost:27017/cyntex", true);
        assertThat(clientSettings.getSslSettings().isEnabled())
                .as("the explicit downgrade connects in plaintext").isFalse();
    }

    @Test
    void aTlsCaFileIsAppliedAsTheTrustContext() throws Exception {
        String uri = "mongodb://localhost:27017/cyntex";
        MongoConnectionSettings settings =
                new MongoConnectionSettings(uri, false, caFixturePath(), Duration.ofMillis(300));
        MongoClientSettings clientSettings =
                new MongoConnection(settings).buildClientSettings(new ConnectionString(uri));
        assertThat(clientSettings.getSslSettings().isEnabled()).isTrue();
        assertThat(clientSettings.getSslSettings().getContext())
                .as("a custom CA file is applied as the TLS trust context (self-signed local chain)")
                .isNotNull();
    }

    @Test
    void anUnreadableTlsCaFileIsReportedAsACodedDiagnostic() {
        String uri = "mongodb://localhost:27017/cyntex";
        MongoConnectionSettings settings = new MongoConnectionSettings(
                uri, false, "/no/such/directory/ca.pem", Duration.ofMillis(300));
        MongoConnection connection = new MongoConnection(settings);
        ConnectionString connectionString = new ConnectionString(uri);
        CyntexException ex = catchThrowableOfType(
                () -> connection.buildClientSettings(connectionString), CyntexException.class);
        assertThat(ex).as("an unreadable CA file surfaces a coded diagnostic, not a bare exception").isNotNull();
        assertThat(ex.code()).isEqualTo(StoreError.TLS_CA_UNREADABLE);
        assertThat(ex.args()).containsKey("path");
    }

    @Test
    void aUriThatDisablesCertificateVerificationWithoutTheDowngradeIsRefusedUpFront() {
        // tlsInsecure / tlsAllowInvalidHostnames turn off certificate/hostname verification while
        // leaving TLS nominally "on". That is an insecure connection, so without the explicit
        // downgrade it is refused up front, exactly like a plaintext one.
        MongoConnectionSettings settings = new MongoConnectionSettings(
                "mongodb://localhost:1/cyntex?tlsInsecure=true", false, null, Duration.ofMillis(300));
        try (MongoConnection connection = new MongoConnection(settings)) {
            CyntexException ex = catchThrowableOfType(connection::verify, CyntexException.class);
            assertThat(ex).as("a verification-disabling URI without the downgrade is refused").isNotNull();
            assertThat(ex.code()).isEqualTo(StoreError.TLS_REQUIRED);
        }
    }

    @Test
    void certificateVerificationIsForcedOnWhenNotDowngraded() {
        // Even if the URI asked to disable hostname verification, the secure path forces it back on —
        // a URI can never quietly weaken the TLS handshake without the explicit downgrade.
        MongoClientSettings clientSettings =
                clientSettingsFor("mongodb://localhost:27017/cyntex?tlsInsecure=true", false);
        assertThat(clientSettings.getSslSettings().isInvalidHostNameAllowed())
                .as("certificate/hostname verification is not silently disabled").isFalse();
    }

    @Test
    void theInsecureDowngradeHonorsDisabledVerification() {
        // With the explicit downgrade the operator owns the risk, so a URI asking to disable
        // verification is honored rather than forced back on.
        MongoClientSettings clientSettings =
                clientSettingsFor("mongodb://localhost:27017/cyntex?tlsInsecure=true", true);
        assertThat(clientSettings.getSslSettings().isInvalidHostNameAllowed())
                .as("the explicit downgrade honors disabled verification").isTrue();
    }

    @Test
    void anEmptyCaFileIsReportedAsACodedDiagnostic() throws Exception {
        // A readable but cert-less CA file parses to zero trust anchors without throwing; left
        // unchecked it would build a trust-nothing context and surface an opaque unreachable. It is
        // an unusable CA file, so it is reported as the coded misconfiguration it is.
        String uri = "mongodb://localhost:27017/cyntex";
        String emptyCa = Path.of(MongoConnectionTest.class.getResource("/tls/empty-ca.pem").toURI()).toString();
        MongoConnectionSettings settings =
                new MongoConnectionSettings(uri, false, emptyCa, Duration.ofMillis(300));
        MongoConnection connection = new MongoConnection(settings);
        ConnectionString connectionString = new ConnectionString(uri);
        CyntexException ex = catchThrowableOfType(
                () -> connection.buildClientSettings(connectionString), CyntexException.class);
        assertThat(ex).as("an empty CA file surfaces a coded diagnostic, not a silent trust-nothing").isNotNull();
        assertThat(ex.code()).isEqualTo(StoreError.TLS_CA_UNREADABLE);
    }

    @Test
    void theTrustManagerFromTheCaFileTrustsOnlyTheSelfSignedChain() throws Exception {
        TrustManager[] trustManagers = MongoConnection.buildTrustManagers(caFixturePath());
        X509TrustManager x509 = (X509TrustManager) trustManagers[0];
        // Exactly the CA in the file is a trusted issuer — a custom trust store built from the
        // self-signed chain, not the JVM-wide default (which carries ~100 public roots).
        assertThat(x509.getAcceptedIssuers())
                .as("only the self-signed CA is trusted").hasSize(1);
        assertThat(x509.getAcceptedIssuers()[0].getSubjectX500Principal().getName())
                .contains("CN=localhost");
    }

    private static MongoClientSettings clientSettingsFor(String uri, boolean allowInsecure) {
        MongoConnectionSettings settings =
                new MongoConnectionSettings(uri, allowInsecure, null, Duration.ofMillis(300));
        return new MongoConnection(settings).buildClientSettings(new ConnectionString(uri));
    }

    private static String caFixturePath() throws Exception {
        return Path.of(MongoConnectionTest.class.getResource("/tls/ca.pem").toURI()).toString();
    }
}
