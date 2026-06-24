package io.cyntex.cli;

import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.model.SourceMode;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.canonical.CanonicalWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interactive source wizard's question flow, driven by a scripted prompter. Asserts on the
 * canonical artifact the collected answers produce — the wizard's job is to build a valid resource.
 */
class SourceWizardTest {

    private static String yaml(SourceResource src) {
        return new CanonicalWriter().write(src);
    }

    @Test
    void buildsAConnectionSupplierSourceWhenTheConnectorHasNoModes() {
        // postgres has an empty capability matrix -> no mode question -> a pure connection supplier
        ScriptedPrompter p = new ScriptedPrompter("postgres", "src_pg");
        SourceResource src = new SourceWizard(p, CyntexCatalog.load()).run();
        assertThat(yaml(src)).isEqualTo(
                """
                version: cyntex/v1
                kind: source
                id: src_pg
                connector: postgres
                """);
    }

    @Test
    void offersAndWritesOnlyTheConnectorsSupportedModes() {
        // mongodb's trustworthy matrix is [cdc, snapshot]; the wizard offers exactly those (plus a
        // "no mode" choice for a connection-supplier source) and writes the picked mode. The tables
        // question follows the mode; a blank answer leaves the source reading every table.
        ScriptedPrompter p = new ScriptedPrompter("mongodb", "cdc", "", "src_mg");
        SourceResource src = new SourceWizard(p, CyntexCatalog.load()).run();

        assertThat(yaml(src)).isEqualTo(
                """
                version: cyntex/v1
                kind: source
                id: src_mg
                connector: mongodb
                mode: cdc
                """);
        // the mode question (the 2nd choose, after the connector list) is pruned to the matrix
        assertThat(p.offered.get(1)).containsExactly("cdc", "snapshot", "(none)");
    }

    @Test
    void collectsConnectorConfigIntoTheSource() {
        // mysql asks deploymentMode (enum) then host; the rest of the spec's fields skip (lenient).
        // a blank tables answer (after the mode) keeps every table in scope
        ScriptedPrompter p = new ScriptedPrompter("mysql", "cdc", "", "src_my", "standalone", "10.0.0.1");
        SourceResource src = new SourceWizard(p, CyntexCatalog.load()).run();

        assertThat(src.connector()).isEqualTo("mysql");
        assertThat(src.mode()).isEqualTo(SourceMode.CDC);
        assertThat(src.config())
                .containsEntry("deploymentMode", "standalone")
                .containsEntry("host", "10.0.0.1");
    }

    @Test
    void collectsTablesWhenAReadModeIsChosen() {
        // with a read mode picked the wizard asks which tables to read: bare names are literal links,
        // /regex/ tokens are dynamic links; the spec's config fields then skip (lenient)
        ScriptedPrompter p = new ScriptedPrompter("mysql", "cdc", "orders, /audit_.*/", "src_my");
        SourceResource src = new SourceWizard(p, CyntexCatalog.load()).run();
        assertThat(yaml(src)).isEqualTo(
                """
                version: cyntex/v1
                kind: source
                id: src_my
                connector: mysql
                mode: cdc
                tables: [orders, /audit_.*/]
                """);
    }

    @Test
    void asksNoTablesForAConnectionSupplierSource() {
        // postgres has no modes -> no read mode -> no tables question. Were tables wrongly asked, the
        // "src_pg" answer would be eaten as a table name and the id would fall back to its default.
        ScriptedPrompter p = new ScriptedPrompter("postgres", "src_pg");
        SourceResource src = new SourceWizard(p, CyntexCatalog.load()).run();
        assertThat(src.tables()).isNull();
        assertThat(src.id()).isEqualTo("src_pg");
    }
}

