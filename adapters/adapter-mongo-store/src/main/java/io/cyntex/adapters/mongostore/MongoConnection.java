package io.cyntex.adapters.mongostore;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.cyntex.core.common.CyntexException;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(b ->
                        b.serverSelectionTimeout(settings.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS));
        // TLS is only turned on here, never off: a tls flag carried in the URI stays honored, and the
        // settings flag forces it on where required; leaving the flag off must not silently disable a
        // TLS request made through the URI.
        if (settings.tls()) {
            builder.applyToSslSettings(b -> b.enabled(true));
        }
        MongoClientSettings clientSettings = builder.build();

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

    @Override
    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
