package io.cyntex.adapters.mongostore;

import java.time.Duration;
import java.util.Objects;

/**
 * The externalized settings for the store connection — the driver-free shape the assembly root
 * binds from configuration and hands to {@link MongoConnection}. Keeping it free of any driver
 * type is what lets the assembly root wire the store without importing the Mongo driver (rule R3).
 *
 * @param uri                    a {@code mongodb://} connection string; it carries the host(s), the
 *                               default database, and the {@code replicaSet} parameter
 * @param tls                    whether to require TLS to the store. This is the reserved SSL slot:
 *                               L1 local runs leave it off, but the field exists so the setting
 *                               aligns with the store implementation, where TLS is mandatory
 * @param serverSelectionTimeout how long connection verification waits for a reachable server
 *                               before reporting the store unreachable (bounds startup fail-fast)
 */
public record MongoConnectionSettings(String uri, boolean tls, Duration serverSelectionTimeout) {

    public MongoConnectionSettings {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(serverSelectionTimeout, "serverSelectionTimeout");
    }
}
