package io.cyntex.tools.catalog.assembler;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.catalog.ConnectorGroup;
import io.cyntex.core.model.SourceMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assembler drives the core normalizer and merge rules over a walk result and a derived
 * capability bitmap, and accounts for every degradation in the report: a database derives its modes,
 * an undeclared message-queue connector is flagged (derived cdc is wrong for it), a JavaScript
 * connector with no derivable capability is unclassified, and a sink with no DML signal is recorded
 * as defaulted — none silently.
 */
class CatalogAssemblerTest {

    private static final String SHA = "20371556";

    // batch + stream + write — a database registers all three.
    private static final Set<String> DB_CAPS =
            Set.of("batch_read_function", "stream_read_function", "write_record_function");

    private static final String MYSQL_SPEC = """
            {"properties":{"id":"mysql","name":"Mysql","realName":"MySQL","icon":"icons/mysql.png",
              "tags":["Database"]},
             "configOptions":{"capabilities":[
                {"id":"dml_insert_policy","alternatives":["update_on_exists","just_insert"]},
                {"id":"dml_update_policy"}],
               "connection":{"properties":{"host":{"type":"string","title":"Host","required":true}}}},
             "messages":{"default":"en_US","en_US":{}}}
            """;

    // Kafka: registers batch+stream+write like a db, but declares no cyntex.modes and has no dml block.
    private static final String KAFKA_SPEC = """
            {"properties":{"id":"kafka","name":"Kafka","realName":"Apache Kafka","icon":"icons/kafka.png",
              "tags":["Database"]},
             "configOptions":{"connection":{"properties":{}}},
             "messages":{"default":"en_US","en_US":{}}}
            """;

    private static final String GITHUB_SPEC = """
            {"properties":{"id":"github","name":"GitHub","icon":"icon/github.png","tags":["SaaS"]},
             "configOptions":{"connection":{"properties":{}}},
             "messages":{"default":"en_US","en_US":{}}}
            """;

    private Assembly assemble() {
        List<ConnectorSource> sources = List.of(
                new ConnectorSource("mysql", "mysql-connector",
                        "connectors/mysql-connector/src/main/resources/mysql-spec.json",
                        "io.tapdata.connector.mysql.MysqlConnector", false),
                new ConnectorSource("kafka", "kafka-connector",
                        "connectors/kafka-connector/src/main/resources/spec_kafka.json",
                        "io.tapdata.connector.kafka.KafkaConnector", false),
                new ConnectorSource("github", "github-connector",
                        "connectors-javascript/github-connector/src/main/resources/spec.json",
                        null, true));
        List<Exemption> exemptions =
                List.of(new Exemption(Exemption.Category.EXCLUDED, "tdd-connector", "known non-connector module"));
        WalkResult walk = new WalkResult(sources, exemptions);

        Map<String, Set<String>> bitmap = Map.of("mysql", DB_CAPS, "kafka", DB_CAPS);
        Map<String, String> specs = Map.of(
                "connectors/mysql-connector/src/main/resources/mysql-spec.json", MYSQL_SPEC,
                "connectors/kafka-connector/src/main/resources/spec_kafka.json", KAFKA_SPEC,
                "connectors-javascript/github-connector/src/main/resources/spec.json", GITHUB_SPEC);

        return CatalogAssembler.assemble(walk, SHA, bitmap, specs::get);
    }

    @Test
    void derivesDatabaseModesAndSinkFromCapabilities() {
        ConnectorCatalogEntry mysql = entry("mysql");
        assertThat(mysql.modes()).containsExactlyInAnyOrder(SourceMode.SNAPSHOT, SourceMode.CDC);
        assertThat(mysql.group()).isEqualTo(ConnectorGroup.DATABASE);
        assertThat(mysql.sink().capable()).isTrue();
    }

    @Test
    void entriesAreSortedByIdAndIndexedInTheReport() {
        assertThat(assemble().entries()).extracting(ConnectorCatalogEntry::id)
                .containsExactly("github", "kafka", "mysql");
        assertThat(assemble().report().ingestedIds()).containsExactly("github", "kafka", "mysql");
    }

    @Test
    void flagsAnUndeclaredMessageQueueConnectorAsAnMqSuspect() {
        // Kafka's name routes it to the MQ group, but with no declaration it derived cdc/snapshot —
        // wrong for a stream source. That must be surfaced, not silently shipped.
        assertThat(entry("kafka").group()).isEqualTo(ConnectorGroup.MQ);
        assertThat(assemble().report().mqSuspects()).contains("kafka");
    }

    @Test
    void reportsAConnectorWithNoResolvableModeAsUnclassified() {
        assertThat(entry("github").modes()).isEmpty();
        assertThat(assemble().report().unclassified()).containsExactly("github");
    }

    @Test
    void recordsASinkDefaultedWithNoDmlSignal() {
        // Kafka is write-capable (derived) but its spec carries no dml policy, so the write semantics
        // were a defaulted superset with no signal — recorded as such. MySQL has dml signals, so it is not.
        assertThat(assemble().report().sinkDefaultedNoSignal()).contains("kafka").doesNotContain("mysql");
    }

    @Test
    void carriesWalkExemptionsIntoTheReport() {
        assertThat(assemble().report().exemptions())
                .anyMatch(e -> e.category() == Exemption.Category.EXCLUDED && e.module().equals("tdd-connector"));
    }

    @Test
    void reportsAJavaConnectorAbsentFromTheBitmapAsNotDerivedNotUnclassified() {
        // hazelcast-connector has a class but is absent from the bitmap — it was not derived this
        // refresh (no jar built, or its jar would not classload). That is a distinct gap from an
        // undeclared connector, so it is reported as "not derived", not lumped into unclassified.
        String hazelcastSpec = """
                {"properties":{"id":"hazelcast","name":"Hazelcast","icon":"icons/hazelcast.png",
                  "tags":["Cache"]},
                 "configOptions":{"connection":{"properties":{}}},
                 "messages":{"default":"en_US","en_US":{}}}
                """;
        List<ConnectorSource> sources = List.of(
                new ConnectorSource("hazelcast", "hazelcast-connector",
                        "connectors/hazelcast-connector/src/main/resources/spec_hazelcast.json",
                        "io.tapdata.connector.hazelcast.HazelcastConnector", false));
        WalkResult walk = new WalkResult(sources, List.of());

        Assembly assembly = CatalogAssembler.assemble(walk, SHA, Map.of(),
                Map.of("connectors/hazelcast-connector/src/main/resources/spec_hazelcast.json",
                        hazelcastSpec)::get);

        assertThat(assembly.report().notDerived()).containsExactly("hazelcast");
        assertThat(assembly.report().unclassified()).doesNotContain("hazelcast");
        // Zero silent drops is two-part: flagged in the report AND still shipped as a (modeless) entry.
        ConnectorCatalogEntry hazelcast = assembly.entries().stream()
                .filter(e -> e.id().equals("hazelcast")).findFirst().orElseThrow();
        assertThat(hazelcast.modes()).isEmpty();
        assertThat(hazelcast.sink().capable()).isFalse();
    }

    private ConnectorCatalogEntry entry(String id) {
        return assemble().entries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }
}
