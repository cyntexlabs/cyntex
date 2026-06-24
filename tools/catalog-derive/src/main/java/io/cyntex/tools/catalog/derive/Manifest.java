package io.cyntex.tools.catalog.derive;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the probe manifest the PDK-free assembler wrote — one {@code id\tmodule\tclass} line per Java
 * connector — into the entries catalog-derive iterates. The reciprocal of the assembler's manifest
 * writer; line-oriented, so parsing is a split and this tool needs no JSON library. A line that is
 * not exactly the three fields is a corrupt hand-off from a misbuilt refresh job, not a recoverable
 * condition, so it fails loud rather than being guessed at. Blank lines are ignored.
 */
final class Manifest {

    private Manifest() {
    }

    static List<ManifestEntry> parse(String tsv) {
        List<ManifestEntry> entries = new ArrayList<>();
        for (String line : tsv.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t");
            if (fields.length != 3) {
                throw new IllegalStateException("malformed manifest line: " + line);
            }
            entries.add(new ManifestEntry(fields[0], fields[1], fields[2]));
        }
        return entries;
    }
}
