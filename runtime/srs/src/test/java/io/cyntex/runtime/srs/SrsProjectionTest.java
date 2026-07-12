package io.cyntex.runtime.srs;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.event.Envelope;
import io.cyntex.core.event.Op;
import io.cyntex.spi.capture.SourcePosition;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Projects a {@link SrsItem} out of the change ring into the transform-facing {@link Envelope}: the
 * position token enters the envelope currency here, the stream name is injected from the source vertex
 * the reader is bound to (the ring is per-table, so the item does not carry it), and schema stays null
 * in the lean tier (the item points at schema history by version rather than repeating it).
 */
class SrsProjectionTest {

    private static SrsItem insert(String token, Map<String, Object> after) {
        return new SrsItem(new SourcePosition(token), Op.INSERT, 100L, null, after, 0L);
    }

    @Test
    void projectsTheSourcePositionTokenIntoTheEnvelope() {
        Envelope e = SrsProjection.toEnvelope(insert("gtid:aaa:99", Map.of("id", 1)), "orders");
        assertThat(e.srcPos()).isEqualTo("gtid:aaa:99");
    }

    @Test
    void injectsTheStreamNameTheItemDoesNotCarry() {
        Envelope e = SrsProjection.toEnvelope(insert("p1", Map.of("id", 1)), "orders");
        assertThat(e.src()).isEqualTo("orders");
    }

    @Test
    void carriesOpTsAndTheAfterImageForAnInsert() {
        Envelope e = SrsProjection.toEnvelope(insert("p1", Map.of("id", 7)), "orders");
        assertThat(e.op()).isEqualTo(Op.INSERT);
        assertThat(e.ts()).isEqualTo(100L);
        assertThat(e.after()).containsEntry("id", 7);
        assertThat(e.before()).isNull();
    }

    @Test
    void carriesBothRowImagesForAnUpdate() {
        SrsItem item = new SrsItem(new SourcePosition("p1"), Op.UPDATE, 1L,
                Map.of("v", "old"), Map.of("v", "new"), 0L);
        Envelope e = SrsProjection.toEnvelope(item, "orders");
        assertThat(e.op()).isEqualTo(Op.UPDATE);
        assertThat(e.before()).containsEntry("v", "old");
        assertThat(e.after()).containsEntry("v", "new");
    }

    @Test
    void carriesTheBeforeImageForADelete() {
        SrsItem item = new SrsItem(new SourcePosition("p1"), Op.DELETE, 1L, Map.of("id", 7), null, 0L);
        Envelope e = SrsProjection.toEnvelope(item, "orders");
        assertThat(e.op()).isEqualTo(Op.DELETE);
        assertThat(e.before()).containsEntry("id", 7);
        assertThat(e.after()).isNull();
    }

    @Test
    void leavesSchemaNullInTheLeanTier() {
        Envelope e = SrsProjection.toEnvelope(insert("p1", Map.of("id", 1)), "orders");
        assertThat(e.schema()).isNull();
    }
}
