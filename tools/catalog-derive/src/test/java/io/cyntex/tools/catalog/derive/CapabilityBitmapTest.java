package io.cyntex.tools.catalog.derive;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The capability bitmap is catalog-derive's output: the connector id to registered capability ids it
 * hands back to the PDK-free assembler. It is a transient build-time file (not checked in, not
 * byte-locked), serialized as one tab-separated line per connector — the id followed by its sorted
 * capability ids. Line-oriented so neither tool needs a JSON library at the hand-off; ids sorted so a
 * refresh diff is stable and readable. A connector that registered nothing is a line with just its id.
 */
class CapabilityBitmapTest {

    @Test
    void serializesIdsAndCapabilitiesSortedOneTabSeparatedLinePerConnector() {
        Map<String, Set<String>> bitmap = new LinkedHashMap<>();
        bitmap.put("mysql", new TreeSet<>(Set.of(
                "stream_read_function", "batch_read_function", "write_record_function")));
        bitmap.put("kafka", new TreeSet<>(Set.of("stream_read_function")));

        assertThat(CapabilityBitmap.serialize(bitmap)).isEqualTo("""
                kafka\tstream_read_function
                mysql\tbatch_read_function\tstream_read_function\twrite_record_function
                """);
    }

    @Test
    void serializesAConnectorWithNoCapabilitiesAsItsIdAlone() {
        assertThat(CapabilityBitmap.serialize(Map.of("empty", Set.of()))).isEqualTo("empty\n");
    }

    @Test
    void serializesAnEmptyBitmapAsEmpty() {
        assertThat(CapabilityBitmap.serialize(Map.of())).isEmpty();
    }
}
