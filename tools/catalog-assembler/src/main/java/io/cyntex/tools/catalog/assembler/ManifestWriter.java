package io.cyntex.tools.catalog.assembler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the probe manifest the PDK-free assembler hands to catalog-derive: the worklist of Java
 * connectors derive must classload to read their capability bitmap. Each line is
 * {@code id\tmodule\tclass} — the id (the bitmap key), the module (so derive resolves the built dist
 * jar) and the fully-qualified connector class (what derive classloads). A line-oriented hand-off
 * keeps derive dependency-free: it parses with a split, no JSON library. JavaScript connectors have
 * no class and are omitted. Lines are sorted by id with the same comparator the walk and assembler
 * use, so the file is deterministic. The shape is the contract between the two tools; catalog-derive
 * reads these same fields.
 */
final class ManifestWriter {

    private ManifestWriter() {
    }

    static String write(List<ConnectorSource> sources) {
        List<ConnectorSource> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparing(ConnectorSource::id));
        StringBuilder sb = new StringBuilder();
        for (ConnectorSource source : ordered) {
            if (source.connectorClassFqn() == null) {
                continue; // no class to classload (a JavaScript connector); nothing to probe
            }
            sb.append(source.id()).append('\t')
                    .append(source.moduleName()).append('\t')
                    .append(source.connectorClassFqn()).append('\n');
        }
        return sb.toString();
    }
}
