package io.cyntex.spi.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SinkConfigTest {

    @Test
    void carriesConnectorSettingsAndWriteIntent() {
        SinkConfig config = new SinkConfig("mysql", Map.of("host", "db"), WriteMode.UPSERT, DdlPolicy.FAIL);

        assertThat(config.connectorId()).isEqualTo("mysql");
        assertThat(config.settings()).containsEntry("host", "db");
        assertThat(config.writeMode()).isEqualTo(WriteMode.UPSERT);
        assertThat(config.ddl()).isEqualTo(DdlPolicy.FAIL);
    }

    @Test
    void nullSettingsBecomeEmpty() {
        assertThat(new SinkConfig("mysql", null, WriteMode.APPEND, DdlPolicy.IGNORE).settings()).isEmpty();
    }

    @Test
    void settingsAreAnUnmodifiableDefensiveCopy() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", "db");
        SinkConfig config = new SinkConfig("mysql", source, WriteMode.APPEND, DdlPolicy.FAIL);

        source.put("host", "changed"); // a later mutation of the caller's map must not leak in

        assertThat(config.settings()).containsEntry("host", "db");
        assertThatThrownBy(() -> config.settings().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresConnectorIdAndWriteIntent() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SinkConfig(null, Map.of(), WriteMode.APPEND, DdlPolicy.FAIL));
        assertThatNullPointerException()
                .isThrownBy(() -> new SinkConfig("mysql", Map.of(), null, DdlPolicy.FAIL));
        assertThatNullPointerException()
                .isThrownBy(() -> new SinkConfig("mysql", Map.of(), WriteMode.APPEND, null));
    }

    @Test
    void carriesTheResolvedTargetTable() {
        TargetTable target = new TargetTable("orders", List.of(new TargetField("id", "int", true)));
        SinkConfig config =
                new SinkConfig("mysql", Map.of(), WriteMode.UPSERT, DdlPolicy.FAIL, target);

        assertThat(config.target()).isEqualTo(target);
    }

    @Test
    void theTargetTableIsOptionalAndAbsentWithoutIt() {
        // The four-arg form omits the target model; a null target means the sink was handed no resolved
        // table model and falls back to a bare table id.
        assertThat(new SinkConfig("mysql", Map.of(), WriteMode.UPSERT, DdlPolicy.FAIL).target()).isNull();
        assertThat(new SinkConfig("mysql", Map.of(), WriteMode.UPSERT, DdlPolicy.FAIL, null).target()).isNull();
    }
}
