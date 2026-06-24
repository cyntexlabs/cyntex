package io.cyntex.tools.catalog.assembler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The walker classifies every candidate module under a connectors checkout: Java connectors resolve
 * to a class + canonical spec, JavaScript connectors to a spec with no class, multi-spec modules
 * keep the canonical one and set the rest aside, and known non-connector modules and spec-less
 * modules are exempted with a reason — nothing silently dropped.
 */
class ConnectorWalkerTest {

    @TempDir
    Path repo;

    @BeforeEach
    void buildFixture() throws IOException {
        // Java DB connector.
        write("connectors/mysql-connector/src/main/java/io/tapdata/connector/mysql/MysqlConnector.java", """
                package io.tapdata.connector.mysql;
                @TapConnectorClass("mysql-spec.json")
                public class MysqlConnector {}
                """);
        write("connectors/mysql-connector/src/main/resources/mysql-spec.json",
                "{\"properties\":{\"id\":\"mysql\"}}");

        // Multi-spec Java connector: v1 annotation commented out, v2 active; spec.json is non-canonical.
        write("connectors/bigquery-connector/src/main/java/io/tapdata/connector/bigquery/BigQueryConnector.java", """
                package io.tapdata.connector.bigquery;
                //@TapConnectorClass("spec.json")
                public class BigQueryConnector {}
                """);
        write("connectors/bigquery-connector/src/main/java/io/tapdata/connector/bigquery/BigQueryConnectorV2.java", """
                package io.tapdata.connector.bigquery;
                @TapConnectorClass("spec-v2.json")
                public class BigQueryConnectorV2 {}
                """);
        write("connectors/bigquery-connector/src/main/resources/spec.json", "{\"properties\":{\"id\":\"bigquery\"}}");
        write("connectors/bigquery-connector/src/main/resources/spec-v2.json", "{\"properties\":{\"id\":\"bigquery\"}}");

        // Known non-connector module (test harness).
        write("connectors/tdd-connector/src/main/resources/sourceSpec.json", "{\"properties\":{\"id\":\"tdd\"}}");

        // A module that looks like a connector but has no resolvable canonical spec.
        write("connectors/orphan-connector/src/main/java/io/tapdata/connector/orphan/Helper.java", """
                package io.tapdata.connector.orphan;
                public class Helper {}
                """);

        // JavaScript connector: spec.json by convention, no Java class.
        write("connectors-javascript/github-connector/src/main/resources/spec.json", "{\"properties\":{\"id\":\"github\"}}");

        // Known shared JS library, excluded.
        write("connectors-javascript/js-core/src/main/resources/util.json", "{}");

        // Non-module infrastructure dir (no src/main): must be skipped, not reported.
        Files.createDirectories(repo.resolve("connectors/dist"));
        Files.writeString(repo.resolve("connectors/dist/mysql-connector-v1.0-SNAPSHOT.jar"), "not-a-module");
    }

    @Test
    void resolvesAJavaConnectorToItsClassAndCanonicalSpec() {
        ConnectorSource mysql = source("mysql");
        assertThat(mysql.moduleName()).isEqualTo("mysql-connector");
        assertThat(mysql.connectorClassFqn()).isEqualTo("io.tapdata.connector.mysql.MysqlConnector");
        assertThat(mysql.specPath()).isEqualTo("connectors/mysql-connector/src/main/resources/mysql-spec.json");
        assertThat(mysql.javascript()).isFalse();
    }

    @Test
    void resolvesAJavaScriptConnectorWithNoClass() {
        ConnectorSource github = source("github");
        assertThat(github.moduleName()).isEqualTo("github-connector");
        assertThat(github.connectorClassFqn()).isNull();
        assertThat(github.javascript()).isTrue();
        assertThat(github.specPath())
                .isEqualTo("connectors-javascript/github-connector/src/main/resources/spec.json");
    }

    @Test
    void picksTheUncommentedSpecAndSetsTheOtherAsideAsMultiSpec() {
        ConnectorSource bigquery = source("bigquery");
        assertThat(bigquery.connectorClassFqn()).isEqualTo("io.tapdata.connector.bigquery.BigQueryConnectorV2");
        assertThat(bigquery.specPath()).endsWith("spec-v2.json");

        assertThat(ConnectorWalker.walk(repo).exemptions())
                .anyMatch(e -> e.category() == Exemption.Category.MULTI_SPEC
                        && e.module().equals("bigquery-connector")
                        && e.detail().contains("spec.json"));
    }

    @Test
    void excludesKnownNonConnectorModules() {
        assertThat(ConnectorWalker.walk(repo).sources())
                .noneMatch(s -> s.moduleName().equals("tdd-connector") || s.moduleName().equals("js-core"));
        assertThat(ConnectorWalker.walk(repo).exemptions())
                .anyMatch(e -> e.category() == Exemption.Category.EXCLUDED && e.module().equals("tdd-connector"))
                .anyMatch(e -> e.category() == Exemption.Category.EXCLUDED && e.module().equals("js-core"));
    }

    @Test
    void reportsAModuleWithNoResolvableSpec() {
        assertThat(ConnectorWalker.walk(repo).sources()).noneMatch(s -> s.moduleName().equals("orphan-connector"));
        assertThat(ConnectorWalker.walk(repo).exemptions())
                .anyMatch(e -> e.category() == Exemption.Category.NO_CANONICAL_SPEC
                        && e.module().equals("orphan-connector"));
    }

    @Test
    void ingestsExactlyTheThreeRealConnectors() {
        assertThat(ConnectorWalker.walk(repo).sources())
                .extracting(ConnectorSource::id)
                .containsExactlyInAnyOrder("mysql", "bigquery", "github");
    }

    private ConnectorSource source(String id) {
        return ConnectorWalker.walk(repo).sources().stream()
                .filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private void write(String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
