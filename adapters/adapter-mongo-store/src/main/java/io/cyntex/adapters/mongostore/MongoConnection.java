package io.cyntex.adapters.mongostore;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
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

    /** The database used when the connection URI names none. */
    private static final String DEFAULT_DATABASE = "cyntex";

    private final MongoConnectionSettings settings;
    private MongoClient client;
    private String databaseName;

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
        // The store database is the one named in the URI, falling back to the default when it names
        // none. Resolved here from the same URI the client uses, so database() reflects the target.
        this.databaseName = resolveDatabaseName(connectionString);
        String target = String.join(",", connectionString.getHosts());
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
     * The verified store database — the one named in the connection URI (falling back to the default
     * when the URI names none). Package-private on purpose: the store adapter binds its collections on
     * it, so a driver type stays inside the module and never reaches the module's public surface.
     * Must be called after {@link #verify()} has opened the client.
     */
    MongoDatabase database() {
        if (client == null) {
            // Called before verify() opened the client: an assembly ordering error, i.e. a programmer
            // bug. It crashes bare with a stack rather than being laundered into a coded diagnostic.
            throw new IllegalStateException("store connection not verified");
        }
        return client.getDatabase(databaseName);
    }

    /** The database named in the connection string, or the default when it names none. */
    static String resolveDatabaseName(ConnectionString connectionString) {
        return connectionString.getDatabase() != null ? connectionString.getDatabase() : DEFAULT_DATABASE;
    }

    /**
     * Builds the driver client settings. TLS is opt-in: it is used only when the URI asks for it
     * ({@code ssl=true} / {@code tls=true}), and its settings are then taken from the URI as-is; the
     * default is a plaintext connection. An explicit CA file trusts a self-signed chain when TLS is on.
     */
    MongoClientSettings buildClientSettings(ConnectionString connectionString) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(b ->
                        b.serverSelectionTimeout(settings.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS));
        // TLS follows the URI (applyConnectionString already applied it). A CA file is meaningful only
        // when TLS is on, so it is applied then and ignored otherwise: it trusts the self-signed chain
        // without touching the JVM-wide trust store.
        boolean useTls = Boolean.TRUE.equals(connectionString.getSslEnabled());
        if (useTls && settings.tlsCaFile() != null) {
            builder.applyToSslSettings(b -> b.context(buildSslContext(settings.tlsCaFile())));
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
