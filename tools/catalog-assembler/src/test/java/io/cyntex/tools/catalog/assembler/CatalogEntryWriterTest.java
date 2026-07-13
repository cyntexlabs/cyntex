package io.cyntex.tools.catalog.assembler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.cyntex.core.catalog.CatalogEntryReader;
import io.cyntex.core.catalog.CatalogEntryWriter;
import io.cyntex.core.catalog.ConfigField;
import io.cyntex.core.catalog.ConfigType;
import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.catalog.ConnectorGroup;
import io.cyntex.core.catalog.Discovery;
import io.cyntex.core.catalog.EnumOption;
import io.cyntex.core.catalog.ModeSource;
import io.cyntex.core.catalog.Provenance;
import io.cyntex.core.catalog.SinkCapability;
import io.cyntex.core.catalog.VisibleWhen;
import io.cyntex.core.model.SourceMode;
import io.cyntex.core.model.WriteMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entry writer is the producer dual of core-catalog's {@link CatalogEntryReader}: writing an
 * entry then reading it back must yield an equal entry, and the bytes must be a stable, fully
 * specified shape (every key present, nulls explicit) so the bundled catalog is byte-lockable.
 */
class CatalogEntryWriterTest {

    private final JsonWriter writer = new JsonWriter();

    @Test
    void writingThenReadingRoundTripsARichEntry() {
        Map<SourceMode, ModeSource> modeSource = new LinkedHashMap<>();
        modeSource.put(SourceMode.CDC, ModeSource.DERIVED);
        modeSource.put(SourceMode.SNAPSHOT, ModeSource.DERIVED);

        ConfigField host = new ConfigField("host", ConfigType.STRING,
                Map.of("en_US", "Host"), true, null, false, List.of(), null);
        ConfigField protocol = new ConfigField("protocol", ConfigType.STRING,
                Map.of("en_US", "Protocol"), false, "ftp", false,
                List.of(new EnumOption("ftp", Map.of("en_US", "FTP")),
                        new EnumOption("sftp", Map.of("en_US", "SFTP"))),
                new VisibleWhen("kind", List.of("remote")));

        ConnectorCatalogEntry entry = new ConnectorCatalogEntry(
                "mysql", "Mysql", "MySQL", "icons/mysql.png",
                ConnectorGroup.DATABASE, List.of(SourceMode.CDC, SourceMode.SNAPSHOT),
                Discovery.CATALOG, new SinkCapability(true, List.of(WriteMode.UPSERT, WriteMode.APPEND)),
                false, List.of(host, protocol),
                new Provenance("20371556", "mysql-connector/src/main/resources/mysql-spec.json",
                        "h-mysql", null, null, modeSource));

        String json = writer.write(CatalogEntryWriter.toTree(entry));

        assertThat(CatalogEntryReader.read(json)).isEqualTo(entry);
    }

    @Test
    void emitsEveryKeyWithNullsExplicitInDeterministicOrder() {
        Map<SourceMode, ModeSource> modeSource = new LinkedHashMap<>();
        modeSource.put(SourceMode.STREAM, ModeSource.DECLARED);

        ConnectorCatalogEntry entry = new ConnectorCatalogEntry(
                "kafka", "Kafka", "Apache Kafka", "icons/kafka.png",
                ConnectorGroup.MQ, List.of(SourceMode.STREAM),
                Discovery.CATALOG, new SinkCapability(true, List.of(WriteMode.UPSERT, WriteMode.APPEND)),
                true, List.of(),
                new Provenance("20371556", "kafka-connector/src/main/resources/spec_kafka.json",
                        "h-kafka", null, null, modeSource));

        assertThat(writer.write(CatalogEntryWriter.toTree(entry))).isEqualTo("""
                {
                  "id": "kafka",
                  "name": "Kafka",
                  "displayName": "Apache Kafka",
                  "icon": "icons/kafka.png",
                  "group": "mq",
                  "modes": [
                    "stream"
                  ],
                  "discovery": "catalog",
                  "sink": {
                    "capable": true,
                    "writeSemantics": [
                      "upsert",
                      "append"
                    ]
                  },
                  "pushOut": true,
                  "config": [],
                  "provenance": {
                    "connectorRepoSha": "20371556",
                    "specPath": "kafka-connector/src/main/resources/spec_kafka.json",
                    "specContentHash": "h-kafka",
                    "pdkApiVersion": null,
                    "requiredLevel": null,
                    "modeSource": {
                      "stream": "declared"
                    }
                  }
                }
                """);
    }
}
