package io.cyntex.core.event;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeTest {

    private static Map<String, Object> row(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    void insertCarriesAfterAndNoBefore() {
        Envelope e = Envelope.insert(100L, "src.customers", row("id", 1), null);
        assertThat(e.op()).isEqualTo(Op.INSERT);
        assertThat(e.ts()).isEqualTo(100L);
        assertThat(e.src()).isEqualTo("src.customers");
        assertThat(e.after()).containsEntry("id", 1);
        assertThat(e.before()).isNull();
    }

    @Test
    void updateCarriesBothBeforeAndAfter() {
        Envelope e = Envelope.update(1L, "s", row("v", "old"), row("v", "new"), null);
        assertThat(e.op()).isEqualTo(Op.UPDATE);
        assertThat(e.before()).containsEntry("v", "old");
        assertThat(e.after()).containsEntry("v", "new");
    }

    @Test
    void deleteCarriesBeforeAndNoAfter() {
        Envelope e = Envelope.delete(1L, "s", row("id", 7), null);
        assertThat(e.op()).isEqualTo(Op.DELETE);
        assertThat(e.before()).containsEntry("id", 7);
        assertThat(e.after()).isNull();
    }

    @Test
    void readCarriesAfterAndNoBefore() {
        Envelope e = Envelope.read(1L, "s", row("id", 7), null);
        assertThat(e.op()).isEqualTo(Op.READ);
        assertThat(e.after()).containsEntry("id", 7);
        assertThat(e.before()).isNull();
    }

    @Test
    void ddlCarriesSchemaAndNeitherRow() {
        Envelope e = Envelope.ddl(1L, "s", row("added_column", "email"));
        assertThat(e.op()).isEqualTo(Op.DDL);
        assertThat(e.before()).isNull();
        assertThat(e.after()).isNull();
        assertThat(e.schema()).containsEntry("added_column", "email");
    }

    @Test
    void rejectsNullOp() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Envelope(null, 1L, "s", null, null, null))
                .withMessageContaining("op");
    }

    @Test
    void rejectsNullSrc() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Envelope(Op.INSERT, 1L, null, null, row("id", 1), null))
                .withMessageContaining("src");
    }

    @Test
    void dataMapsAreUnmodifiable() {
        Envelope e = Envelope.insert(1L, "s", row("id", 1), null);
        assertThatThrownBy(() -> e.after().put("x", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defensivelyCopiesSoLaterMutationOfTheSourceMapDoesNotLeakIn() {
        Map<String, Object> after = row("id", 1);
        Envelope e = Envelope.insert(1L, "s", after, null);
        after.put("id", 999);
        assertThat(e.after()).containsEntry("id", 1);
    }

    @Test
    void valueEqualityByContent() {
        Envelope a = Envelope.insert(5L, "s", row("id", 1), null);
        Envelope b = Envelope.insert(5L, "s", row("id", 1), null);
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }
}
