package io.cyntex.adapters.mongostore;

import java.time.Duration;
import java.util.Objects;

/**
 * The externalized settings for the store connection — the driver-free shape the assembly root
 * binds from configuration and hands to {@link MongoConnection}. Keeping it free of any driver
 * type is what lets the assembly root wire the store without importing the Mongo driver (rule R3).
 *
 * <p>TLS to the store is mandatory: TLS is on by default, and a configuration that would reach the
 * store over plaintext is refused rather than silently allowed. Plaintext is reached only by the
 * explicit {@code allowInsecure} downgrade — a loud, deliberate opt-out, never a silent default.
 *
 * @param uri                    a {@code mongodb://} connection string; it carries the host(s), the
 *                               default database, and the {@code replicaSet} parameter
 * @param allowInsecure          the explicit downgrade permitting a plaintext connection. Off by
 *                               default: with it off, a URI that turns TLS off is refused up front,
 *                               and an unspecified URI still connects over TLS. Turn it on only to
 *                               deliberately reach a plaintext store (e.g. a local development one)
 * @param tlsCaFile              an optional path to a PEM CA certificate to trust for the TLS
 *                               handshake — the local development self-signed chain. {@code null}
 *                               falls back to the JVM default trust store
 * @param serverSelectionTimeout how long connection verification waits for a reachable server
 *                               before reporting the store unreachable (bounds startup fail-fast)
 */
public record MongoConnectionSettings(
        String uri, boolean allowInsecure, String tlsCaFile, Duration serverSelectionTimeout) {

    public MongoConnectionSettings {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(serverSelectionTimeout, "serverSelectionTimeout");
    }
}
