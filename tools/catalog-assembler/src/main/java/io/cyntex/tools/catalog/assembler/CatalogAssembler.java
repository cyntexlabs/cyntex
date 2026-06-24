package io.cyntex.tools.catalog.assembler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.cyntex.core.catalog.CatalogEntryAssembler;
import io.cyntex.core.catalog.CatalogJson;
import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.catalog.ConnectorGroup;
import io.cyntex.core.catalog.NormalizedSpec;
import io.cyntex.core.catalog.SpecNormalizer;
import io.cyntex.core.model.SourceMode;

/**
 * Drives the catalog assembly: for each walked connector it parses the spec (reusing core-catalog's
 * JSON reader), normalizes it, merges the derived capability bitmap and any declared modes via the
 * core merge rules, and stamps provenance. Alongside the entries it builds the ingest report,
 * surfacing every degradation — unclassified connectors, undeclared message-queue suspects, sinks
 * defaulted with no DML signal, unrecognized type tokens and unresolved label refs — so nothing is
 * lost silently. Pure: file reads are the caller's, supplied as {@code specContent}.
 */
final class CatalogAssembler {

    private static final String STREAM_READ = "stream_read_function";
    private static final String WRITE_RECORD = "write_record_function";

    private CatalogAssembler() {
    }

    static Assembly assemble(WalkResult walk, String connectorRepoSha,
                             Map<String, Set<String>> bitmap,
                             Function<String, String> specContent) {
        List<ConnectorCatalogEntry> entries = new ArrayList<>();
        List<String> ingestedIds = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();
        List<String> notDerived = new ArrayList<>();
        List<String> mqSuspects = new ArrayList<>();
        List<String> sinkDefaultedNoSignal = new ArrayList<>();
        List<String> unknownTypeFields = new ArrayList<>();
        List<String> unresolvedLabelRefs = new ArrayList<>();

        List<ConnectorSource> ordered = new ArrayList<>(walk.sources());
        ordered.sort(java.util.Comparator.comparing(ConnectorSource::id));
        for (ConnectorSource source : ordered) {
            String content = specContent.apply(source.specPath());
            Map<String, Object> tree = asMap(CatalogJson.parse(content));
            NormalizedSpec spec = SpecNormalizer.normalize(tree);
            Set<String> caps = bitmap.getOrDefault(source.id(), Set.of());
            String hash = sha256(content);

            ConnectorCatalogEntry entry =
                    CatalogEntryAssembler.assemble(spec, caps, connectorRepoSha, source.specPath(), hash);
            entries.add(entry);
            ingestedIds.add(entry.id());

            // A Java connector (has a class) absent from the bitmap was not derived this refresh — its
            // jar was not built, or it would not classload (a platform-excluded build). That is a
            // distinct gap from a connector that was probed (or is JavaScript) and still resolved no
            // mode, so keep the two apart rather than lumping a build gap into unclassified.
            boolean notDerivedThisRun = source.connectorClassFqn() != null && !bitmap.containsKey(source.id());
            if (notDerivedThisRun) {
                notDerived.add(entry.id());
            } else if (entry.modes().isEmpty()) {
                unclassified.add(entry.id());
            }
            if (isMqSuspect(entry, spec, caps)) {
                mqSuspects.add(entry.id());
            }
            if (sinkDefaultedNoSignal(spec, caps)) {
                sinkDefaultedNoSignal.add(entry.id());
            }
            SpecAuditor.Findings findings = SpecAuditor.audit(tree);
            findings.unknownTypeFields().forEach(f -> unknownTypeFields.add(entry.id() + ":" + f));
            findings.unresolvedLabelRefs().forEach(k -> unresolvedLabelRefs.add(entry.id() + ":" + k));
        }

        IngestReport report = new IngestReport(connectorRepoSha, ingestedIds, unclassified, notDerived,
                mqSuspects, sinkDefaultedNoSignal, unknownTypeFields, unresolvedLabelRefs, walk.exemptions());
        return new Assembly(entries, report);
    }

    /** A stream-reading connector that the merge routed to the MQ group but that declared no modes —
     *  so it derived cdc/snapshot, which is wrong for a stream source and must be reviewed. */
    private static boolean isMqSuspect(ConnectorCatalogEntry entry, NormalizedSpec spec, Set<String> caps) {
        return entry.group() == ConnectorGroup.MQ
                && caps.contains(STREAM_READ)
                && spec.declaredModes() == null
                && !entry.modes().contains(SourceMode.STREAM);
    }

    /** Write-capable but the spec carries no DML policy, so the write semantics were a defaulted
     *  superset with no signal — indistinguishable from a real one without this flag. */
    private static boolean sinkDefaultedNoSignal(NormalizedSpec spec, Set<String> caps) {
        return caps.contains(WRITE_RECORD)
                && spec.dmlInsertAlternatives().isEmpty()
                && !spec.hasDmlUpdatePolicy();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** First 16 hex chars of the spec's SHA-256 — enough to detect any upstream content change. */
    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
