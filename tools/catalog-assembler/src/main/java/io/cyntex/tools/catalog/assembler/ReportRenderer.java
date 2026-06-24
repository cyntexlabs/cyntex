package io.cyntex.tools.catalog.assembler;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders an {@link IngestReport} to deterministic markdown — fixed sections in a fixed order, items
 * sorted, and an explicit {@code (none)} for an empty section so it reads as "checked, nothing found"
 * rather than a gap. Checked in beside the catalog and byte-locked, so it must be stable.
 */
final class ReportRenderer {

    private ReportRenderer() {
    }

    static String render(IngestReport report) {
        StringBuilder head = new StringBuilder();
        head.append("# Connector catalog ingest report\n\n");
        head.append("Connector repo SHA: `").append(report.connectorRepoSha()).append("`\n");
        head.append("Ingested connectors: ").append(report.ingestedIds().size()).append("\n\n");

        List<String> sections = new ArrayList<>();
        sections.add(section("Unclassified — no resolvable mode (need cyntex.modes)", report.unclassified()));
        sections.add(section("Not derived — no built jar or did not classload (excluded from refresh)",
                report.notDerived()));
        sections.add(section("MQ suspects — derived cdc, undeclared (need cyntex.modes)", report.mqSuspects()));
        sections.add(section("Sink semantics defaulted — no DML signal", report.sinkDefaultedNoSignal()));
        sections.add(section("Unrecognized type tokens — fell to string input", report.unknownTypeFields()));
        sections.add(section("Unresolved label refs — fell back to raw key", report.unresolvedLabelRefs()));
        sections.add(section("Exemptions — modules and specs set aside", exemptionLines(report.exemptions())));

        return head + String.join("\n", sections);
    }

    private static String section(String title, List<String> items) {
        StringBuilder sb = new StringBuilder("## ").append(title).append('\n');
        if (items.isEmpty()) {
            sb.append("(none)\n");
        } else {
            items.stream().sorted().forEach(item -> sb.append("- ").append(item).append('\n'));
        }
        return sb.toString();
    }

    private static List<String> exemptionLines(List<Exemption> exemptions) {
        List<String> lines = new ArrayList<>();
        for (Exemption exemption : exemptions) {
            lines.add("[" + exemption.category() + "] " + exemption.module() + ": " + exemption.detail());
        }
        return lines;
    }
}
