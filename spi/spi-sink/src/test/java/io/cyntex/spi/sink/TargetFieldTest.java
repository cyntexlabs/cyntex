package io.cyntex.spi.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TargetFieldTest {

    @Test
    void carriesNameTypeAndPrimaryKeyFlag() {
        TargetField field = new TargetField("id", "int", true);

        assertThat(field.name()).isEqualTo("id");
        assertThat(field.type()).isEqualTo("int");
        assertThat(field.primaryKey()).isTrue();
    }

    @Test
    void typeMayBeNullWhenUnresolved() {
        assertThat(new TargetField("id", null, false).type()).isNull();
    }

    @Test
    void requiresANonBlankName() {
        assertThatThrownBy(() -> new TargetField(" ", "int", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetField(null, "int", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
