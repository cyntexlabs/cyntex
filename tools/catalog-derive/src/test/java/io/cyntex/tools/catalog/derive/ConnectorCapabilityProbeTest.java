package io.cyntex.tools.catalog.derive;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the catalog-derive premise: a connector can be classloaded from its dist jar and its
 * capability bitmap read by running {@code registerCapabilities} offline, with no database
 * connection. These tests need the {@code tapdata-connectors} checkout (dist jars) alongside this
 * repo; they skip when it is absent so the default build stays connector-free.
 */
class ConnectorCapabilityProbeTest {

    @Test
    void mysqlConnectorRegistersBatchStreamAndWriteCapabilities() {
        Path jar = distJar("mysql-connector-");
        Set<String> caps = ConnectorCapabilityProbe.probe(jar, "io.tapdata.connector.mysql.MysqlConnector");

        // A database connector registers batch read (snapshot), stream read (cdc) and write record
        // (sink); deriving these offline is the whole point of the derive segment.
        assertThat(caps).contains("batch_read_function", "stream_read_function", "write_record_function");
    }

    @Test
    void kafkaConnectorAlsoRegistersStreamReadProvingItIsNotCdcSpecific() {
        Path jar = distJar("kafka-connector-");
        Set<String> caps = ConnectorCapabilityProbe.probe(jar, "io.tapdata.connector.kafka.KafkaConnector");

        // Kafka registers stream read too, yet its semantics are `stream`, not `cdc` — so streamRead
        // alone cannot distinguish the two. This is why stream/api/file must be declared, not derived.
        assertThat(caps).contains("stream_read_function");
    }

    @Test
    void cloudVariantInheritsCapabilitiesThroughTheClassChain() {
        // AWSRDSMySQLConnector extends MysqlConnector and does not override registerCapabilities, so
        // its capabilities live only in the inherited method. Classloading runs that inherited method,
        // so the variant derives the same database capabilities — a static single-file scan would miss
        // them. This is why deriving must classload, not parse source.
        Path jar = distJar("aws-rds-mysql-connector-");
        Set<String> caps = ConnectorCapabilityProbe.probe(jar, "io.tapdata.connector.rds.AWSRDSMySQLConnector");

        assertThat(caps).contains("batch_read_function", "stream_read_function", "write_record_function");
    }

    /** Locates a dist jar by file-name prefix, skipping the test when the connectors checkout is absent. */
    private static Path distJar(String prefix) {
        Optional<Path> dist = connectorsDist();
        assumeTrue(dist.isPresent(), "tapdata-connectors dist not found alongside this repo — skipping");
        try (Stream<Path> jars = Files.list(dist.get())) {
            Optional<Path> jar = jars
                    .filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".jar"))
                    .findFirst();
            assumeTrue(jar.isPresent(), "dist jar " + prefix + "*.jar not found — skipping");
            return jar.get();
        } catch (IOException e) {
            throw new RuntimeException("listing dist dir " + dist.get(), e);
        }
    }

    /** Walks up from the module dir looking for a sibling {@code tapdata-connectors/connectors/dist}. */
    private static Optional<Path> connectorsDist() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("tapdata-connectors").resolve("connectors").resolve("dist");
            if (Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
