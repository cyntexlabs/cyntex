package io.cyntex.app;

import io.cyntex.adapters.mongostore.MongoConnection;
import io.cyntex.adapters.mongostore.MongoConnectionSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the store connection into the assembly root. Under {@code --role=all} the server connects to
 * the store at startup and fails fast — as a coded diagnostic — if it cannot (see
 * {@link CodedFailureAnalyzer}). The connection is closed when the context shuts down.
 *
 * <p>Gated on {@code cyntex.store.mongo.enabled} (on by default): a run that has no store — a
 * substrate check, say — turns it off and starts without one.
 */
@Configuration
@EnableConfigurationProperties(MongoProperties.class)
class StoreConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "cyntex.store.mongo", name = "enabled", matchIfMissing = true)
    MongoConnection storeConnection(MongoProperties properties) {
        MongoConnection connection = new MongoConnection(new MongoConnectionSettings(
                properties.getUri(), properties.isTls(), properties.getServerSelectionTimeout()));
        // Fail fast at startup: a coded diagnostic surfaces through CodedFailureAnalyzer if the
        // store is unreachable or is not a replica-set, rather than a bare driver stack trace.
        connection.verify();
        return connection;
    }
}
