package io.cyntex.tools.catalog.assembler;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ingest report is a checked-in, byte-locked audit artifact, so its rendering must be
 * deterministic: fixed sections in a fixed order, items sorted, and an explicit "(none)" where a
 * degradation did not occur — so an empty section reads as "checked, nothing found", never as a gap.
 */
class ReportRendererTest {

    @Test
    void rendersEverySectionDeterministicallyWithNoneWhereEmpty() {
        IngestReport report = new IngestReport(
                "20371556abc",
                List.of("github", "kafka", "mysql"),
                List.of("github"),
                List.of("hazelcast"),
                List.of("kafka"),
                List.of("kafka"),
                List.of(),
                List.of(),
                List.of(new Exemption(Exemption.Category.EXCLUDED, "tdd-connector", "known non-connector module")));

        assertThat(ReportRenderer.render(report)).isEqualTo("""
                # Connector catalog ingest report

                Connector repo SHA: `20371556abc`
                Ingested connectors: 3

                ## Unclassified — no resolvable mode (need cyntex.modes)
                - github

                ## Not derived — no built jar or did not classload (excluded from refresh)
                - hazelcast

                ## MQ suspects — derived cdc, undeclared (need cyntex.modes)
                - kafka

                ## Sink semantics defaulted — no DML signal
                - kafka

                ## Unrecognized type tokens — fell to string input
                (none)

                ## Unresolved label refs — fell back to raw key
                (none)

                ## Exemptions — modules and specs set aside
                - [EXCLUDED] tdd-connector: known non-connector module
                """);
    }
}
