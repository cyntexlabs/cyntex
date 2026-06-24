package io.cyntex.tools.catalog.derive;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reads the probe manifest the assembler wrote — one {@code id\tmodule\tclass} line per Java
 * connector — into the entries catalog-derive iterates. The reciprocal of the assembler's manifest
 * writer; parsing is a split, so catalog-derive needs no JSON library. A line that is not exactly the
 * three fields is a corrupt hand-off and fails loud rather than being guessed at.
 */
class ManifestTest {

    @Test
    void parsesEachTabSeparatedLineIntoAProbeEntry() {
        List<ManifestEntry> entries = Manifest.parse("""
                kafka\tkafka-connector\tio.tapdata.connector.kafka.KafkaConnector
                mysql\tmysql-connector\tio.tapdata.connector.mysql.MysqlConnector
                """);

        assertThat(entries).containsExactly(
                new ManifestEntry("kafka", "kafka-connector", "io.tapdata.connector.kafka.KafkaConnector"),
                new ManifestEntry("mysql", "mysql-connector", "io.tapdata.connector.mysql.MysqlConnector"));
    }

    @Test
    void parsesAnEmptyManifestAsNoEntries() {
        assertThat(Manifest.parse("")).isEmpty();
    }

    @Test
    void toleratesCrlfLineEndingsWithoutCorruptingTheClassField() {
        // A reformatted or autocrlf-touched hand-off must not leave a trailing \r on the FQN, which
        // would then fail to classload and silently degrade the connector.
        List<ManifestEntry> entries = Manifest.parse(
                "kafka\tkafka-connector\tio.tapdata.connector.kafka.KafkaConnector\r\n");

        assertThat(entries).containsExactly(
                new ManifestEntry("kafka", "kafka-connector", "io.tapdata.connector.kafka.KafkaConnector"));
    }

    @Test
    void failsLoudOnAMalformedLineRatherThanGuessing() {
        assertThatThrownBy(() -> Manifest.parse("mysql\tmysql-connector\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mysql-connector");
    }
}
