package io.cyntex.core.catalog;

import static io.cyntex.core.model.WriteMode.APPEND;
import static io.cyntex.core.model.WriteMode.UPSERT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SinkRulesTest {

    @Test
    void aConnectorWithoutWriteRecordIsNotASink() {
        SinkCapability sink = SinkRules.derive(false, List.of("update_on_exists"), true);

        assertThat(sink.capable()).isFalse();
        assertThat(sink.writeSemantics()).isEmpty();
    }

    @Test
    void aDatabaseSinkThatCanUpdateOnExistsSupportsUpsertAndAppend() {
        // mysql: dml_insert_policy alternatives include update_on_exists, plus a dml_update_policy.
        SinkCapability sink = SinkRules.derive(true,
                List.of("update_on_exists", "ignore_on_exists", "just_insert"), true);

        assertThat(sink.capable()).isTrue();
        // Declaration order is upsert, append (WriteMode order) so the catalog is deterministic.
        assertThat(sink.writeSemantics()).containsExactly(UPSERT, APPEND);
    }

    @Test
    void anInsertOnlySinkSupportsOnlyAppend() {
        // A sink whose only insert disposition is just_insert and which has no update policy can
        // only append — it cannot match-and-update by key.
        SinkCapability sink = SinkRules.derive(true, List.of("just_insert"), false);

        assertThat(sink.capable()).isTrue();
        assertThat(sink.writeSemantics()).containsExactly(APPEND);
    }

    @Test
    void aSinkWithNoDmlSignalDefaultsToBothModes() {
        // ~30% of connectors carry no capabilities block; a sink with write_record but no DML
        // policy defaults to the common superset rather than silently dropping upsert.
        SinkCapability sink = SinkRules.derive(true, null, false);

        assertThat(sink.capable()).isTrue();
        assertThat(sink.writeSemantics()).containsExactly(UPSERT, APPEND);
    }

    @Test
    void aDmlUpdatePolicyAloneIsEnoughForUpsert() {
        // An insert policy without update_on_exists but with a dml_update_policy still handles
        // keyed updates, so upsert is supported.
        SinkCapability sink = SinkRules.derive(true, List.of("just_insert", "ignore_on_exists"), true);

        assertThat(sink.capable()).isTrue();
        assertThat(sink.writeSemantics()).containsExactly(UPSERT, APPEND);
    }
}
