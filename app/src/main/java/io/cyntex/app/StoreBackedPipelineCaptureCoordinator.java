package io.cyntex.app;

import io.cyntex.core.event.Envelope;
import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.ReadMode;
import io.cyntex.core.model.Settings;
import io.cyntex.core.model.SourceResource;
import io.cyntex.runtime.srs.CaptureRun;
import io.cyntex.runtime.srs.CaptureRunSpec;
import io.cyntex.runtime.srs.SrsCoordinator;
import io.cyntex.runtime.srs.StartFrom;
import io.cyntex.spi.capture.SourcePosition;
import io.cyntex.spi.store.ArtifactStore;
import io.cyntex.spi.store.StorePort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The store-backed capture coordinator: it resolves a pipeline and the sources it reads from the store,
 * starts one cdc capture run per source through the capture seam, and holds the live handles so a stop can
 * tear them down. It derives each source run spec identically to how the topology builder derives the ring
 * the run fills, through the shared source resolution, so the capture and the reader agree on the ring.
 *
 * <p>The run spec carries L1 mock collaborators standing in for real connector machinery: a fixed cdc-start
 * token, a monotonic watermark generator, and a position order that ranks those watermark tokens by numeric
 * suffix (never lexically). The watermark token format and the position order are a matched pair. Snapshot
 * rows drain to a no-op pass-through here; routing the snapshot phase through the pipeline's transform chain
 * is a later increment.
 */
final class StoreBackedPipelineCaptureCoordinator implements PipelineCaptureCoordinator {

    /** The fixed cdc-start position for an L1 run: a mock stand-in for the position sampled at snapshot start. */
    private static final SourcePosition MOCK_CDC_START = new SourcePosition("cdc-start-0");

    /** The schema version stamped on ring items at L1 (schema evolution is a later increment). */
    private static final long MOCK_SCHEMA_VER = 0L;

    /**
     * Orders the mock watermark positions {@code w1 < w2 < ...} by numeric suffix. A source position is opaque
     * and never ordered lexically -- lexical order would rank {@code w10} before {@code w2}. Matched to the
     * {@code w<n>} token the watermark generator produces.
     */
    private static final Comparator<String> MOCK_POSITION_ORDER =
            Comparator.comparingInt(token -> Integer.parseInt(token.substring(1)));

    private final StorePort storePort;
    private final CaptureStarter captureStarter;
    private final SrsCoordinator srsCoordinator;
    private final Map<String, List<CaptureRun>> runsByPipeline = new ConcurrentHashMap<>();

    StoreBackedPipelineCaptureCoordinator(
            StorePort storePort, CaptureStarter captureStarter, SrsCoordinator srsCoordinator) {
        this.storePort = Objects.requireNonNull(storePort, "storePort");
        this.captureStarter = Objects.requireNonNull(captureStarter, "captureStarter");
        this.srsCoordinator = Objects.requireNonNull(srsCoordinator, "srsCoordinator");
    }

    @Override
    public void startCapture(String pipelineId) {
        // Idempotent: a pipeline whose capture is already running is left running, so a repeated start does not
        // open a second capture behind the one already filling the ring.
        if (runsByPipeline.containsKey(pipelineId)) {
            return;
        }
        PipelineResource pipeline = StoredArtifacts.requirePipeline(artifacts(), pipelineId);
        List<CaptureRun> runs = new ArrayList<>();
        for (String sourceId : pipeline.sources()) {
            SourceResource source = StoredArtifacts.requireSource(artifacts(), sourceId);
            CaptureRunSpec spec = deriveSpec(pipelineId, pipeline.settings(), source, SourceCaptureResolution.of(source));
            runs.add(captureStarter.start(spec, NO_OP_PASSTHROUGH));
        }
        runsByPipeline.put(pipelineId, runs);
    }

    @Override
    public void stopCapture(String pipelineId) {
        List<CaptureRun> runs = runsByPipeline.remove(pipelineId);
        if (runs == null) {
            return;
        }
        for (CaptureRun run : runs) {
            // Close first: stops the capture daemon so no thread leaks. Then release this pipeline's consumer
            // membership and tear the source chain down -- a shared-ring run only; a run that opened no chain
            // has nothing to release.
            run.close();
            run.chainId().ifPresent(chainId -> {
                srsCoordinator.detachConsumer(chainId, pipelineId);
                srsCoordinator.teardownSource(chainId);
            });
        }
    }

    /**
     * Derives one source run spec from the source, the pipeline settings, and the shared resolution. The read
     * axis comes from settings (read mode defaulting to snapshot-then-cdc, start position to earliest); srs is
     * on unless the source declares it off; the L1 mock collaborators are fresh per run.
     */
    static CaptureRunSpec deriveSpec(
            String pipelineId, Settings settings, SourceResource source, SourceCaptureResolution resolution) {
        ReadMode readMode = settings != null && settings.readMode() != null
                ? settings.readMode() : ReadMode.SNAPSHOT_AND_CDC;
        String startFromRaw = settings != null && settings.startFrom() != null
                ? settings.startFrom() : "earliest";
        String retention = source.srs() != null ? source.srs().retention() : null;
        return new CaptureRunSpec(
                resolution.config(),
                readMode,
                resolution.srsKey(),
                srsEnabled(source),
                resolution.sourceId(),
                pipelineId,
                StartFrom.parse(startFromRaw),
                MOCK_CDC_START,
                retention,
                MOCK_SCHEMA_VER,
                monotonicWatermark(),
                MOCK_POSITION_ORDER);
    }

    /** Whether this pipeline currently has a live capture -- a test-visible view of the retained handles. */
    boolean isActive(String pipelineId) {
        return runsByPipeline.containsKey(pipelineId);
    }

    /** SRS is on unless the source declares an srs block that sets {@code enabled:false}. */
    private static boolean srsEnabled(SourceResource source) {
        if (source.srs() == null) {
            return true;
        }
        Boolean enabled = source.srs().enabled();
        return enabled == null || enabled;
    }

    /** A mock cdc watermark: a monotonic source-position generator (w1, w2, ...) standing in for the connector. */
    private static Supplier<SourcePosition> monotonicWatermark() {
        AtomicLong counter = new AtomicLong();
        return () -> new SourcePosition("w" + counter.incrementAndGet());
    }

    private ArtifactStore artifacts() {
        return storePort.artifacts();
    }

    /** Snapshot rows drain here at L1; routing the snapshot through the transform chain is a later increment. */
    private static final Consumer<Envelope> NO_OP_PASSTHROUGH = event -> { };
}
