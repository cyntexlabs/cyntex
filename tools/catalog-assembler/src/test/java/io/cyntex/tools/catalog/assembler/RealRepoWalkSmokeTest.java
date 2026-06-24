package io.cyntex.tools.catalog.assembler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Walks the real tapdata-connectors checkout (skipped when it is absent) to prove discovery holds
 * against reality: known connectors resolve to the right class and canonical spec, multi-spec and
 * non-connector modules are handled, and parsing every real spec to read its id does not blow up.
 * No classloading, so this stays PDK-free.
 */
class RealRepoWalkSmokeTest {

    @Test
    void resolvesKnownConnectorsAndParsesEverySpec() {
        Optional<Path> repo = connectorsRepo();
        assumeTrue(repo.isPresent(), "tapdata-connectors checkout not found alongside this repo — skipping");

        WalkResult walk = ConnectorWalker.walk(repo.get());

        // Ids are kept as the spec declares them (case preserved), so the JS connector is "GitHub".
        assertThat(walk.sources()).extracting(ConnectorSource::id)
                .contains("mysql", "kafka", "bigquery", "GitHub")
                .doesNotContain("tdd");
        assertThat(walk.sources()).hasSizeGreaterThan(60);

        ConnectorSource mysql = source(walk, "mysql");
        assertThat(mysql.connectorClassFqn()).isEqualTo("io.tapdata.connector.mysql.MysqlConnector");
        assertThat(mysql.javascript()).isFalse();

        ConnectorSource github = source(walk, "GitHub");
        assertThat(github.javascript()).isTrue();
        assertThat(github.connectorClassFqn()).isNull();

        // bigquery resolves to v2 (v1 annotation is commented out) and v1's spec.json is set aside.
        assertThat(source(walk, "bigquery").connectorClassFqn()).endsWith("BigQueryConnectorV2");
        assertThat(walk.exemptions())
                .anyMatch(e -> e.category() == Exemption.Category.MULTI_SPEC
                        && e.module().equals("bigquery-connector"));
    }

    private static ConnectorSource source(WalkResult walk, String id) {
        return walk.sources().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static Optional<Path> connectorsRepo() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("tapdata-connectors");
            if (Files.isDirectory(candidate.resolve("connectors"))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
