package io.cyntex.core.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CyntexSchemaTest {

    @Test
    void servesTheBundledCyntexV1Schema() {
        // The runtime loads the checked-in artifact; it must be exactly what the generator produces.
        assertThat(CyntexSchema.json()).isEqualTo(new SchemaGenerator().generate());
    }

    @Test
    void exposesTheSchemaId() {
        assertThat(CyntexSchema.id()).isEqualTo("https://cyntex.io/schema/cyntex/v1.json");
        assertThat(CyntexSchema.json())
                .contains("\"$id\": \"https://cyntex.io/schema/cyntex/v1.json\"");
    }
}
