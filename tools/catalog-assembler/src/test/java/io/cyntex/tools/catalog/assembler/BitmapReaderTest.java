package io.cyntex.tools.catalog.assembler;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derived capability bitmap is the data hand-off from catalog-derive (which classloads, touching
 * PDK) to this PDK-free assembler: one tab-separated line per connector, the id followed by its
 * registered capability ids. Line-oriented so derive needs no JSON library; this reads it back into
 * the map the assembler merges. A connector that registered nothing is a line with just its id.
 */
class BitmapReaderTest {

    @Test
    void readsTabSeparatedConnectorIdsToCapabilityIdSets() {
        Map<String, Set<String>> bitmap = BitmapReader.read("""
                mysql\tbatch_read_function\tstream_read_function\twrite_record_function
                github
                """);

        assertThat(bitmap).containsOnlyKeys("mysql", "github");
        assertThat(bitmap.get("mysql"))
                .containsExactlyInAnyOrder("batch_read_function", "stream_read_function", "write_record_function");
        assertThat(bitmap.get("github")).isEmpty();
    }

    @Test
    void readsAnEmptyBitmapAsAnEmptyMap() {
        assertThat(BitmapReader.read("")).isEmpty();
    }

    @Test
    void toleratesCrlfLineEndingsWithoutCorruptingTheLastCapability() {
        // A trailing \r on the last capability would no longer equal the function constants the merge
        // keys off, mis-deriving modes/sink — so CRLF must be stripped.
        Map<String, Set<String>> bitmap = BitmapReader.read("mysql\tbatch_read_function\twrite_record_function\r\n");

        assertThat(bitmap.get("mysql")).containsExactlyInAnyOrder("batch_read_function", "write_record_function");
    }
}
