package io.cyntex.spi.store;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaVersionTest {

    @Test
    void holdsTheVersionSchemaAndDdlSeq() {
        SchemaVersion version = new SchemaVersion(3L, Map.of("id", "long", "name", "string"), 128L);
        assertThat(version.version()).isEqualTo(3L);
        assertThat(version.schema()).containsEntry("id", "long").containsEntry("name", "string");
        assertThat(version.ddlSeq()).isEqualTo(128L);
    }

    @Test
    void copiesTheSchemaSoALaterMutationDoesNotLeakIn() {
        Map<String, Object> live = new HashMap<>();
        live.put("id", "long");
        SchemaVersion version = new SchemaVersion(1L, live, 0L);
        live.put("id", "string");
        assertThat(version.schema()).containsEntry("id", "long");
    }

    @Test
    void rejectsMutationOfTheReturnedSchema() {
        SchemaVersion version = new SchemaVersion(1L, Map.of("id", "long"), 0L);
        assertThatThrownBy(() -> version.schema().put("name", "string"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsANullSchema() {
        assertThatThrownBy(() -> new SchemaVersion(1L, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANegativeVersion() {
        assertThatThrownBy(() -> new SchemaVersion(-1L, Map.of(), 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANegativeDdlSeq() {
        assertThatThrownBy(() -> new SchemaVersion(1L, Map.of(), -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
