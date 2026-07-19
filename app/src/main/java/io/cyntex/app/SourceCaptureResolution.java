package io.cyntex.app;

import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.TableRef;
import io.cyntex.runtime.srs.MiningChainId;
import io.cyntex.runtime.srs.SrsRingbuffer;
import io.cyntex.spi.capture.CaptureConfig;
import java.util.List;

/**
 * How a pipeline-referenced source resolves into its capture/read ring identity: the connector config, the
 * single table it reads at L1, the srs key, and the mining-chain id that keys its per-table change ring. The
 * side that fills the ring (capture) and the side that reads it (the source vertex) each resolve the source
 * independently, so deriving this identity in one place is what guarantees they land on the same ring rather
 * than drifting apart.
 *
 * <p>L1 shape: a source reads exactly one table. An explicit {@code srs.key} keys the chain directly;
 * otherwise it derives from the connector-and-settings content hash.
 *
 * @param sourceId the resource id of the source
 * @param config   the connector, its settings, and the single stream to read
 * @param table    the single table this source reads
 * @param srsKey   the explicit mining-chain key, or null to derive from the config hash
 * @param chainId  the mining-chain id both the writer and the reader key the ring under
 */
record SourceCaptureResolution(
        String sourceId, CaptureConfig config, String table, String srsKey, MiningChainId chainId) {

    static SourceCaptureResolution of(SourceResource source) {
        String table = singleTable(source);
        CaptureConfig config = new CaptureConfig(source.connector(), source.config(), List.of(table));
        String srsKey = source.srs() != null ? source.srs().key() : null;
        return new SourceCaptureResolution(source.id(), config, table, srsKey, MiningChainId.resolve(config, srsKey));
    }

    /** The per-table change ring name both the capture writer and the source-vertex reader look the ring up by. */
    String ringName() {
        return SrsRingbuffer.ringName(chainId.value(), table);
    }

    /** The one table an L1 source reads; a missing, multi-table or regex selector is out of scope here. */
    private static String singleTable(SourceResource source) {
        List<TableRef> tables = source.tables();
        if (tables == null || tables.size() != 1) {
            throw new IllegalStateException("source '" + source.id() + "' must read exactly one table, declares "
                    + (tables == null ? 0 : tables.size()));
        }
        return switch (tables.get(0)) {
            case TableRef.Literal literal -> literal.name();
            case TableRef.Spec spec -> spec.name();
            case TableRef.Regex regex -> throw new IllegalStateException("source '" + source.id()
                    + "' selects tables by regex, which the linear L1 builder does not carry: " + regex.pattern());
        };
    }
}
