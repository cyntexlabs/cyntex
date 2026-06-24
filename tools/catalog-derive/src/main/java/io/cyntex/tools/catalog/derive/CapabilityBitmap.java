package io.cyntex.tools.catalog.derive;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Serializes the derived capability bitmap — connector id to the capability ids its
 * {@code registerCapabilities} registered — to the transient text the PDK-free assembler reads back.
 * One tab-separated line per connector: the id followed by its sorted capability ids. Line-oriented
 * so neither tool needs a JSON library at the hand-off; ids sorted so a refresh diff is stable. The
 * bitmap is transient (not checked in), so it is not byte-locked; the determinism is for legibility.
 * A connector that registered nothing is a line with just its id.
 */
final class CapabilityBitmap {

    private CapabilityBitmap() {
    }

    static String serialize(Map<String, Set<String>> bitmap) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> entry : new TreeMap<>(bitmap).entrySet()) {
            sb.append(entry.getKey());
            for (String capability : new TreeSet<>(entry.getValue())) {
                sb.append('\t').append(capability);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
