package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectionConfig;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The connection-document codec is the mapping core of the catalog store: a connection config is
 * stored as a structured document — its id as {@code _id}, its connector id and settings as fields —
 * and reconstructed from it on read. These witness the mapping deterministically, without a Mongo
 * server, including that a settings sub-document the driver hands back with bson {@code Document} /
 * list values is normalized to plain Java maps and lists, so no driver type escapes the module. A
 * real Mongo round-trip is exercised by {@code MongoCatalogStoreIT} (skipped where Docker is absent).
 *
 * <p>Unlike the artifact store, a connection config has no canonical serialization contract, so it is
 * stored as a structured document — which also lets the catalog be queried by connector id later —
 * rather than as a canonical text body.
 */
class MongoCatalogStoreTest {

    @Test
    void documentCarriesIdConnectorIdAndSettings() {
        ConnectionConfig config = new ConnectionConfig(
                "orders-db", "mysql", Map.of("host", "db.local", "port", 3306, "tls", true));

        Document document = MongoCatalogStore.toDocument(config);

        assertThat(document.getString("_id")).isEqualTo("orders-db");
        assertThat(document.getString("connectorId")).isEqualTo("mysql");
        assertThat(document.get("settings", Document.class))
                .containsEntry("host", "db.local")
                .containsEntry("port", 3306)
                .containsEntry("tls", true);
    }

    @Test
    void roundTripReconstructsTheSameConfigForFlatScalarSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("host", "db.local");
        settings.put("port", 3306);
        settings.put("tls", true);
        ConnectionConfig config = new ConnectionConfig("orders-db", "mysql", settings);

        assertThat(MongoCatalogStore.toConfig(MongoCatalogStore.toDocument(config))).isEqualTo(config);
    }

    @Test
    void emptySettingsRoundTrip() {
        ConnectionConfig config = new ConnectionConfig("bare", "postgres", Map.of());

        assertThat(MongoCatalogStore.toConfig(MongoCatalogStore.toDocument(config))).isEqualTo(config);
    }

    @Test
    void readNormalizesDriverDocumentAndListSettingsToPlainJavaTypes() {
        // What the driver hands back on read: nested maps decode to bson Document and lists to
        // List<Document>. The mapping must normalize these to plain maps and lists, so no driver
        // type is reachable through the returned config's settings.
        Document stored = new Document("_id", "orders-db")
                .append("connectorId", "mysql")
                .append("settings", new Document("host", "db.local")
                        .append("pool", new Document("min", 1).append("max", 8))
                        .append("hosts", List.of(new Document("h", "a"), new Document("h", "b"))));

        ConnectionConfig config = MongoCatalogStore.toConfig(stored);

        Object pool = config.settings().get("pool");
        assertThat(pool).isInstanceOf(Map.class).isNotInstanceOf(Document.class);
        Map<?, ?> poolMap = (Map<?, ?>) pool;
        assertThat(poolMap.get("min")).isEqualTo(1);
        assertThat(poolMap.get("max")).isEqualTo(8);

        Object hosts = config.settings().get("hosts");
        assertThat(hosts).isInstanceOf(List.class);
        assertThat(((List<?>) hosts).get(0)).isInstanceOf(Map.class).isNotInstanceOf(Document.class);
    }

    @Test
    void toConfigOnADocumentMissingConnectorIdIsDocumentUnreadable() {
        Document corrupt = new Document("_id", "orders-db").append("settings", new Document("host", "db.local"));

        Throwable thrown = catchThrowable(() -> MongoCatalogStore.toConfig(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", "orders-db");
    }

    @Test
    void readNormalizesBsonNativeLeafTypesToPlainJavaTypes() {
        // A stored settings value that decodes to a driver-native leaf type (a foreign or corrupt
        // document) must not escape as an org.bson type; it is represented as a plain Java value.
        Document stored = new Document("_id", "x").append("connectorId", "mysql")
                .append("settings", new Document("dec", new Decimal128(new BigDecimal("1.5")))
                        .append("oid", new ObjectId("507f1f77bcf86cd799439011"))
                        .append("bin", new Binary("abc".getBytes(StandardCharsets.UTF_8))));

        ConnectionConfig config = MongoCatalogStore.toConfig(stored);

        assertThat(config.settings().get("dec")).isEqualTo(new BigDecimal("1.5"));
        assertThat(config.settings().get("oid")).isEqualTo("507f1f77bcf86cd799439011");
        assertThat(config.settings().get("bin")).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));
        for (Object value : config.settings().values()) {
            assertThat(value.getClass().getPackageName()).doesNotStartWith("org.bson");
        }
    }

    @Test
    void toConfigWithAbsentSettingsFieldReadsBackEmpty() {
        Document document = new Document("_id", "bare").append("connectorId", "postgres");

        assertThat(MongoCatalogStore.toConfig(document).settings()).isEmpty();
    }

    @Test
    void toConfigOnACorruptSettingsFieldIsDocumentUnreadable() {
        // settings present but not a sub-document is corruption — surfaced as unreadable rather than
        // silently coerced to an empty-settings config.
        Document corrupt = new Document("_id", "x").append("connectorId", "mysql").append("settings", "oops");

        Throwable thrown = catchThrowable(() -> MongoCatalogStore.toConfig(corrupt));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }
}
