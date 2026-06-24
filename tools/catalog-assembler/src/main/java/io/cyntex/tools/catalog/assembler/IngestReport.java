package io.cyntex.tools.catalog.assembler;

import java.util.List;

/**
 * The accounting of one catalog assembly run: what was ingested and every degradation that must be
 * visible rather than silent — connectors with no resolvable mode (unclassified), Java connectors
 * whose capabilities could not be derived this refresh because their jar was not built or would not
 * classload (not derived), message-queue connectors derived as cdc because they declare no
 * {@code cyntex.modes} (mq suspects), sinks whose write semantics were defaulted with no DML signal,
 * unrecognized Formily type tokens and unresolved i18n label refs, plus the modules the walk set
 * aside. Checked in beside the catalog so every gap is reviewable in the PR.
 */
record IngestReport(String connectorRepoSha,
                    List<String> ingestedIds,
                    List<String> unclassified,
                    List<String> notDerived,
                    List<String> mqSuspects,
                    List<String> sinkDefaultedNoSignal,
                    List<String> unknownTypeFields,
                    List<String> unresolvedLabelRefs,
                    List<Exemption> exemptions) {
}
