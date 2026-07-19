package io.cyntex.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.model.SourceMode;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.Srs;
import io.cyntex.core.model.TableRef;
import io.cyntex.runtime.srs.MiningChainId;
import io.cyntex.runtime.srs.SrsRingbuffer;
import io.cyntex.spi.capture.CaptureConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one place a pipeline-referenced source resolves into its capture/read ring identity. The reader that
 * builds the source vertex and the capture side that fills the ring must derive the identical ring from the
 * same source, so this pins the derivation: connector + settings + single table into a mining-chain id and
 * the per-table ring name, deterministically, with the explicit srs key overriding config-hash derivation.
 */
class SourceCaptureResolutionTest {

    @Test
    void derivesConnectorConfigAndSingleTableFromTheSource() {
        SourceResource source = cdcSource("orders_src", "orders", null);

        SourceCaptureResolution resolution = SourceCaptureResolution.of(source);

        assertThat(resolution.sourceId()).isEqualTo("orders_src");
        assertThat(resolution.table()).isEqualTo("orders");
        assertThat(resolution.config().connectorId()).isEqualTo("mysql");
        assertThat(resolution.config().settings()).containsEntry("host", "h");
        assertThat(resolution.config().streams()).containsExactly("orders");
        assertThat(resolution.srsKey()).isNull();
    }

    @Test
    void derivesTheRingIdentityFromTheConfigHashWhenNoSrsKeyIsSet() {
        SourceResource source = cdcSource("orders_src", "orders", null);

        SourceCaptureResolution resolution = SourceCaptureResolution.of(source);

        CaptureConfig config = new CaptureConfig("mysql", Map.of("host", "h"), List.of("orders"));
        MiningChainId expected = MiningChainId.resolve(config, null);
        assertThat(resolution.chainId()).isEqualTo(expected);
        assertThat(resolution.ringName()).isEqualTo(SrsRingbuffer.ringName(expected.value(), "orders"));
    }

    @Test
    void anExplicitSrsKeyOverridesTheConfigHashDerivation() {
        SourceResource source = cdcSource("orders_src", "orders", "shared-key");

        SourceCaptureResolution resolution = SourceCaptureResolution.of(source);

        assertThat(resolution.srsKey()).isEqualTo("shared-key");
        assertThat(resolution.chainId()).isEqualTo(MiningChainId.ofKey("shared-key"));
    }

    @Test
    void twoResolutionsOfTheSameSourceDeriveTheIdenticalRingName() {
        SourceResource source = cdcSource("orders_src", "orders", null);

        // The load-bearing contract: the reader and the capture side each resolve the source independently and
        // must land on the same ring -- deriving one identity in one place is what guarantees it.
        assertThat(SourceCaptureResolution.of(source).ringName())
                .isEqualTo(SourceCaptureResolution.of(source).ringName());
    }

    @Test
    void rejectsASourceThatDoesNotReadExactlyOneTable() {
        SourceResource multiTable = new SourceResource("orders_src", null, "mysql", Map.of("host", "h"),
                SourceMode.CDC, List.of(TableRef.literal("orders"), TableRef.literal("items")), null, null, null);

        assertThatThrownBy(() -> SourceCaptureResolution.of(multiTable))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one table");
    }

    private static SourceResource cdcSource(String id, String table, String srsKey) {
        Srs srs = srsKey == null ? null : new Srs(srsKey, null, null, null, null);
        return new SourceResource(id, null, "mysql", Map.of("host", "h"), SourceMode.CDC,
                List.of(TableRef.literal(table)), null, srs, null);
    }
}
