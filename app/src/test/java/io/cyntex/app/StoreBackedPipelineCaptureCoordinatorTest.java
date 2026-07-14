package io.cyntex.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.ReadMode;
import io.cyntex.core.model.ServeBlock;
import io.cyntex.core.model.Settings;
import io.cyntex.core.model.SourceMode;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.Srs;
import io.cyntex.core.model.SyncElement;
import io.cyntex.core.model.TableRef;
import io.cyntex.runtime.srs.CaptureRun;
import io.cyntex.runtime.srs.CaptureRunSpec;
import io.cyntex.runtime.srs.MiningChainId;
import io.cyntex.runtime.srs.SrsCoordinator;
import io.cyntex.core.model.FromRef;
import io.cyntex.spi.capture.SourcePosition;
import io.cyntex.spi.capture.Subscription;
import io.cyntex.spi.store.StorePort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The app-side capture coordinator: how it derives a source run spec from a stored source and the pipeline
 * settings (including the L1 mock collaborators that stand in for real connector machinery), and how it holds
 * the live capture handles it starts so a stop can tear them down. Spec derivation is a pure function tested
 * directly; the handle lifecycle is driven over an in-memory store and a fake capture starter, so it needs no
 * running Jet member.
 */
class StoreBackedPipelineCaptureCoordinatorTest {

    // ---- spec derivation -------------------------------------------------------------------------

    @Test
    void derivesTheRunSpecFromTheSourceAndPipelineSettings() {
        SourceResource source = cdcSource("orders_src", "orders", null);
        Settings settings = new Settings(null, null, null, null, ReadMode.CDC_ONLY, "earliest");

        CaptureRunSpec spec = StoreBackedPipelineCaptureCoordinator.deriveSpec(
                "pipe-1", settings, source, SourceCaptureResolution.of(source));

        assertThat(spec.pipelineId()).isEqualTo("pipe-1");
        assertThat(spec.sourceId()).isEqualTo("orders_src");
        assertThat(spec.config().connectorId()).isEqualTo("mysql");
        assertThat(spec.config().streams()).containsExactly("orders");
        assertThat(spec.readMode()).isEqualTo(ReadMode.CDC_ONLY);
        assertThat(spec.srsKey()).isNull();
        assertThat(spec.srsEnabled()).as("srs defaults on when the source declares no srs block").isTrue();
        assertThat(spec.startFrom()).isEqualTo(io.cyntex.runtime.srs.StartFrom.earliest());
        assertThat(spec.schemaVer()).isZero();
    }

    @Test
    void theL1MockWatermarkIsMonotonicAndItsOrderIsNumericNotLexical() {
        SourceResource source = cdcSource("orders_src", "orders", null);
        Settings settings = new Settings(null, null, null, null, ReadMode.CDC_ONLY, "earliest");

        CaptureRunSpec spec = StoreBackedPipelineCaptureCoordinator.deriveSpec(
                "pipe-1", settings, source, SourceCaptureResolution.of(source));

        // The mock watermark is a monotonic source-position generator; the position order and the token format
        // are a matched pair, ranking by numeric suffix -- never lexically (where w10 would sort before w2).
        assertThat(spec.watermark().get()).isEqualTo(new SourcePosition("w1"));
        assertThat(spec.watermark().get()).isEqualTo(new SourcePosition("w2"));
        assertThat(spec.cdcStart()).isNotNull();
        assertThat(spec.positionOrder().compare("w2", "w10")).isNegative();
        assertThat("w2".compareTo("w10")).as("plain lexical order would rank w10 before w2").isPositive();
    }

    @Test
    void aSourceWithSrsDisabledDerivesTheDirectTailAndAnExplicitKeyIsCarried() {
        SourceResource source = new SourceResource("orders_src", null, "mysql", Map.of("host", "h"),
                SourceMode.CDC, List.of(TableRef.literal("orders")), null,
                new Srs("shared-key", null, null, null, false), null);

        CaptureRunSpec spec = StoreBackedPipelineCaptureCoordinator.deriveSpec(
                "pipe-1", null, source, SourceCaptureResolution.of(source));

        assertThat(spec.srsEnabled()).as("srs.enabled:false is honoured").isFalse();
        assertThat(spec.srsKey()).isEqualTo("shared-key");
        assertThat(spec.readMode()).as("null settings default the read mode").isEqualTo(ReadMode.SNAPSHOT_AND_CDC);
        assertThat(spec.startFrom()).as("null settings default the start position to earliest")
                .isEqualTo(io.cyntex.runtime.srs.StartFrom.earliest());
    }

    // ---- handle lifecycle ------------------------------------------------------------------------

    @Test
    void startRetainsALiveHandleAndStopClosesItThenTearsTheChainDown() {
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.save(cdcSource("orders_src", "orders", null));
        artifacts.save(pipeline("p", "orders_src"));
        StorePort store = artifactsOnly(artifacts);

        SrsCoordinator srsCoordinator = new SrsCoordinator(new InMemorySrsMetaStore());
        AtomicBoolean subscriptionClosed = new AtomicBoolean(false);
        AtomicReference<CaptureRunSpec> startedSpec = new AtomicReference<>();
        // A fake capture starter mirrors what the real run unit does to the coordinator -- provision the chain
        // and attach the consumer -- and hands back a run whose subscription records that it was closed.
        CaptureStarter starter = (spec, passthrough) -> {
            startedSpec.set(spec);
            MiningChainId chainId = MiningChainId.resolve(spec.config(), spec.srsKey());
            srsCoordinator.provisionSource(spec.sourceId(), chainId, spec.config().streams(), spec.retention());
            srsCoordinator.attachConsumer(chainId, spec.pipelineId());
            Subscription subscription = () -> subscriptionClosed.set(true);
            return new CaptureRun(Optional.of(chainId), false, 0L, Optional.empty(), Optional.of(subscription));
        };
        StoreBackedPipelineCaptureCoordinator coordinator =
                new StoreBackedPipelineCaptureCoordinator(store, starter, srsCoordinator);

        coordinator.startCapture("p");

        MiningChainId chainId = MiningChainId.resolve(startedSpec.get().config(), startedSpec.get().srsKey());
        assertThat(coordinator.isActive("p")).as("start retains a live handle for the pipeline").isTrue();
        assertThat(srsCoordinator.isProvisioned(chainId)).isTrue();

        coordinator.stopCapture("p");

        assertThat(subscriptionClosed).as("stop closes the capture subscription, stopping the daemon").isTrue();
        assertThat(srsCoordinator.isProvisioned(chainId)).as("stop tears the source chain down").isFalse();
        assertThat(coordinator.isActive("p")).as("stop drops the handle").isFalse();
    }

    @Test
    void stopIsANoOpForAPipelineThatWasNeverStarted() {
        StoreBackedPipelineCaptureCoordinator coordinator = new StoreBackedPipelineCaptureCoordinator(
                artifactsOnly(new InMemoryArtifactStore()),
                (spec, passthrough) -> {
                    throw new AssertionError("no capture should be started");
                },
                new SrsCoordinator(new InMemorySrsMetaStore()));

        coordinator.stopCapture("never-started");

        assertThat(coordinator.isActive("never-started")).isFalse();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static SourceResource cdcSource(String id, String table, String srsKey) {
        Srs srs = srsKey == null ? null : new Srs(srsKey, null, null, null, null);
        return new SourceResource(id, null, "mysql", Map.of("host", "h"), SourceMode.CDC,
                List.of(TableRef.literal(table)), null, srs, null);
    }

    private static PipelineResource pipeline(String id, String sourceId) {
        return new PipelineResource(id, null, List.of(sourceId), null, null,
                new ServeBlock.Inline(null, FromRef.literal(sourceId),
                        List.of(new SyncElement("sync_1", sourceId, null, null, null, null)), null, null),
                new Settings(null, null, null, null, ReadMode.CDC_ONLY, "earliest"), null);
    }

    private static StorePort artifactsOnly(InMemoryArtifactStore artifacts) {
        return new InMemoryStorePort(artifacts);
    }
}
