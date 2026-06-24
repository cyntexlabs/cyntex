package io.cyntex.tools.catalog.assembler;

import java.util.List;

/**
 * The outcome of walking a connectors checkout: the connectors resolved as ingestable, plus every
 * module or spec set aside with a reason. Together they account for every candidate the walk saw.
 */
record WalkResult(List<ConnectorSource> sources, List<Exemption> exemptions) {
}
