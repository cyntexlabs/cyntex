package io.cyntex.adapters.mongostore;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.cyntex.core.common.CyntexException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.bson.Document;

/**
 * The store connection substrate: opens a Mongo client from the externalized settings and verifies
 * the target is reachable and is a replica-set (the checkpoint compare-and-swap runs in a
 * multi-document transaction, which requires one). It is the assembly root's driver-free handle on
 * the store — the public surface exposes only java types, so no driver type escapes this module
 * (rule R3). Driver failures are translated into {@code store.*} coded diagnostics.
 *
 * <p>This is the connection substrate only; the production compare-and-swap store implementation
 * lands later.
 */
public final class MongoConnection implements AutoCloseable {

    private final MongoConnectionSettings settings;
    private MongoClient client;

    public MongoConnection(MongoConnectionSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Opens the client and verifies the store is reachable and is a replica-set. Raises a
     * {@code store.unreachable} coded diagnostic if the target cannot be reached within the
     * configured server-selection timeout, or {@code store.not-replica-set} if it is reached but
     * is a standalone server. On success the client is held open for the process lifetime.
     */
    public void verify() {
        // A repeated verify() must not orphan a previously opened client (its pool and monitor threads).
        close();

        ConnectionString connectionString;
        try {
            connectionString = new ConnectionString(settings.uri());
        } catch (IllegalArgumentException e) {
            // The URI is operator-supplied config; a malformed one is a diagnosable misconfiguration.
            // Carry no detail — the raw URI could embed a credential.
            throw new CyntexException(StoreError.INVALID_URI, Map.of(), e);
        }
        String target = String.join(",", connectionString.getHosts());
        // A secure TLS connection to the store is mandatory: a URI that would reach it insecurely —
        // plaintext (ssl=false), or with certificate/hostname verification disabled (tlsInsecure /
        // tlsAllowInvalidHostnames) — is refused up front, before any connection attempt, unless the
        // operator opted into an insecure connection with the explicit downgrade. This is what keeps
        // an insecure store link from being made silently.
        boolean plaintext = Boolean.FALSE.equals(connectionString.getSslEnabled());
        boolean verificationDisabled = Boolean.TRUE.equals(connectionString.getSslInvalidHostnameAllowed());
        if ((plaintext || verificationDisabled) && !settings.allowInsecure()) {
            throw new CyntexException(StoreError.TLS_REQUIRED, Map.of("target", target), null);
        }
        MongoClientSettings clientSettings = buildClientSettings(connectionString);

        MongoClient opened = MongoClients.create(clientSettings);
        Document hello;
        try {
            hello = opened.getDatabase("admin").runCommand(new Document("hello", 1));
        } catch (MongoException e) {
            opened.close();
            throw new CyntexException(StoreError.UNREACHABLE, Map.of("target", target), e);
        }
        // A replica-set member reports its set name in the hello response; a standalone does not.
        if (!hello.containsKey("setName")) {
            opened.close();
            throw new CyntexException(StoreError.NOT_REPLICA_SET, Map.of("target", target), null);
        }
        this.client = opened;
    }

    /**
     * Builds the driver client settings, applying the mandatory-TLS policy: TLS is on by default and
     * a TLS request carried in the URI is always honored; it is turned off only by the explicit
     * insecure downgrade.
     */
    MongoClientSettings buildClientSettings(ConnectionString connectionString) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(b ->
                        b.serverSelectionTimeout(settings.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS));
        boolean useTls = Boolean.TRUE.equals(connectionString.getSslEnabled()) || !settings.allowInsecure();
        if (useTls) {
            builder.applyToSslSettings(b -> {
                b.enabled(true);
                // A URI must never quietly weaken the handshake: unless the operator opted into an
                // insecure connection, certificate/hostname verification is forced on regardless of
                // any tlsInsecure/tlsAllowInvalidHostnames the URI carried.
                if (!settings.allowInsecure()) {
                    b.invalidHostNameAllowed(false);
                }
                // An explicit CA file trusts a self-signed chain (the local development one); with no
                // CA file the handshake falls back to the JVM default trust store.
                if (settings.tlsCaFile() != null) {
                    b.context(buildSslContext(settings.tlsCaFile()));
                }
            });
        }
        return builder.build();
    }

    /**
     * Builds a TLS context trusting only the CA certificate(s) in {@code caFile} (a PEM file). This is
     * how the local development self-signed chain is trusted without touching the JVM-wide trust store.
     */
    private static SSLContext buildSslContext(String caFile) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, buildTrustManagers(caFile), null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new CyntexException(StoreError.TLS_CA_UNREADABLE, Map.of("path", caFile), e);
        }
    }

    /**
     * Builds trust managers trusting only the CA certificate(s) in {@code caFile} (a PEM file) — the
     * self-signed local development chain — kept out of the JVM-wide trust store.
     */
    static TrustManager[] buildTrustManagers(String caFile) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int index = 0;
            try (InputStream in = Files.newInputStream(Path.of(caFile))) {
                for (Certificate certificate : factory.generateCertificates(in)) {
                    trust.setCertificateEntry("ca-" + index++, certificate);
                }
            }
            if (index == 0) {
                // A readable but cert-less PEM parses to zero anchors without throwing; a trust store
                // with nothing in it would silently distrust every server, so it is an unusable CA file.
                throw new CyntexException(StoreError.TLS_CA_UNREADABLE, Map.of("path", caFile), null);
            }
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            return trustManagers.getTrustManagers();
        } catch (GeneralSecurityException | IOException e) {
            // The CA file is operator-supplied config; an unreadable or malformed one is a
            // diagnosable misconfiguration, not a programmer bug. The path is a filesystem path,
            // not a credential, so it is safe to echo back.
            throw new CyntexException(StoreError.TLS_CA_UNREADABLE, Map.of("path", caFile), e);
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
