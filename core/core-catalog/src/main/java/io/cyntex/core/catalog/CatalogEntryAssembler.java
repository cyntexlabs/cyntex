package io.cyntex.core.catalog;

import java.util.List;
import java.util.Set;

import io.cyntex.core.model.SourceMode;

/**
 * Merges a connector's structural facts ({@link NormalizedSpec}) with its derived capability bitmap
 * into a {@link ConnectorCatalogEntry}: resolves modes (derived defaults or declared override),
 * sink capability and write semantics, the refined group, the discovery axis and the push-out flag,
 * then stamps provenance. This is the shared merge — the same rules the runtime server-register path
 * will reuse — so it lives in the core ring and depends on no build tooling.
 */
public final class CatalogEntryAssembler {

    private CatalogEntryAssembler() {
    }

    public static ConnectorCatalogEntry assemble(NormalizedSpec spec,
                                                 Set<String> derivedCapabilityIds,
                                                 String connectorRepoSha,
                                                 String specPath,
                                                 String specContentHash) {
        Set<DerivedCapability> capabilities = DerivedCapability.fromCapabilityIds(derivedCapabilityIds);
        ModeResolution modeResolution = ModeResolver.resolve(capabilities, spec.declaredModes());
        List<SourceMode> modes = List.copyOf(modeResolution.modes());

        SinkCapability sink = SinkRules.derive(
                capabilities.contains(DerivedCapability.WRITE_RECORD),
                spec.dmlInsertAlternatives(),
                spec.hasDmlUpdatePolicy());

        ConnectorGroup group = GroupRules.refine(spec.tagGroup(), modeResolution.modes(), spec.id());
        Discovery discovery = DiscoveryRules.fromGroup(group);
        // A message-queue connector is the one kind that can be a push (event-stream) target.
        boolean pushOut = group == ConnectorGroup.MQ;

        Provenance provenance = new Provenance(connectorRepoSha, specPath, specContentHash,
                null, null, modeResolution.bySource());

        return new ConnectorCatalogEntry(spec.id(), spec.name(), spec.displayName(), spec.icon(),
                group, modes, discovery, sink, pushOut, spec.config(), provenance);
    }
}
