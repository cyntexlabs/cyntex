package io.cyntex.tools.catalog.assembler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the derived capability bitmap — the tab-separated lines of connector id followed by its
 * registered capability ids that catalog-derive produces — back into the map the assembler merges. A
 * connector that registered nothing is a line with just its id (an empty capability set). Blank lines
 * are ignored. Line-oriented so the hand-off needs no JSON library on either side.
 */
final class BitmapReader {

    private BitmapReader() {
    }

    static Map<String, Set<String>> read(String tsv) {
        Map<String, Set<String>> bitmap = new LinkedHashMap<>();
        for (String line : tsv.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t");
            Set<String> capabilities = new TreeSet<>();
            for (int i = 1; i < fields.length; i++) {
                capabilities.add(fields[i]);
            }
            bitmap.put(fields[0], capabilities);
        }
        return bitmap;
    }
}
