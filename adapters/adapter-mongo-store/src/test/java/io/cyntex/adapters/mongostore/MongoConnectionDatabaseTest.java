package io.cyntex.adapters.mongostore;

import com.mongodb.ConnectionString;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store database is the one named in the connection URI, falling back to the default when the URI
 * names none; and the database handle is only available after the connection has been verified —
 * asking for it before is a programmer / ordering error that crashes bare rather than being coded.
 */
class MongoConnectionDatabaseTest {

    @Test
    void resolvesTheDatabaseNamedInTheUri() {
        assertThat(MongoConnection.resolveDatabaseName(new ConnectionString("mongodb://localhost:27017/orders")))
                .isEqualTo("orders");
    }

    @Test
    void fallsBackToTheDefaultWhenTheUriNamesNoDatabase() {
        assertThat(MongoConnection.resolveDatabaseName(new ConnectionString("mongodb://localhost:27017")))
                .isEqualTo("cyntex");
    }

    @Test
    void databaseIsUnavailableBeforeTheConnectionIsVerified() {
        MongoConnection connection = new MongoConnection(new MongoConnectionSettings(
                "mongodb://localhost:27017/cyntex", true, null, Duration.ofSeconds(5)));
        assertThatThrownBy(connection::database).isInstanceOf(IllegalStateException.class);
    }
}
