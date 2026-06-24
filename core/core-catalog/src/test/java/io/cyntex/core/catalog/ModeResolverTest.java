package io.cyntex.core.catalog;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import io.cyntex.core.model.SourceMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModeResolverTest {

    @Test
    void databaseConnectorDerivesSnapshotAndCdcFromBatchAndStreamRead() {
        ModeResolution resolution = ModeResolver.resolve(
                EnumSet.of(DerivedCapability.BATCH_READ, DerivedCapability.STREAM_READ,
                        DerivedCapability.WRITE_RECORD),
                null);

        assertThat(resolution.modes()).containsExactlyInAnyOrder(SourceMode.SNAPSHOT, SourceMode.CDC);
        assertThat(resolution.source(SourceMode.SNAPSHOT)).isEqualTo(ModeSource.DERIVED);
        assertThat(resolution.source(SourceMode.CDC)).isEqualTo(ModeSource.DERIVED);
    }

    @Test
    void declaredModesReplaceDerivedDefaultsEntirely() {
        // Kafka registers batch+stream+write, which would derive snapshot+cdc, but it declares
        // `stream`. The declaration is the complete mode set: the derived guesses are dropped.
        ModeResolution resolution = ModeResolver.resolve(
                EnumSet.of(DerivedCapability.BATCH_READ, DerivedCapability.STREAM_READ,
                        DerivedCapability.WRITE_RECORD),
                List.of("stream"));

        assertThat(resolution.modes()).containsExactly(SourceMode.STREAM);
        assertThat(resolution.source(SourceMode.STREAM)).isEqualTo(ModeSource.DECLARED);
        assertThat(resolution.source(SourceMode.CDC)).isNull();
        assertThat(resolution.source(SourceMode.SNAPSHOT)).isNull();
    }

    @Test
    void snapshotOnlySourceDerivesOnlySnapshot() {
        ModeResolution resolution = ModeResolver.resolve(EnumSet.of(DerivedCapability.BATCH_READ), null);

        assertThat(resolution.modes()).containsExactly(SourceMode.SNAPSHOT);
    }

    @Test
    void sinkOnlyConnectorHasNoSourceModes() {
        ModeResolution resolution = ModeResolver.resolve(EnumSet.of(DerivedCapability.WRITE_RECORD), null);

        assertThat(resolution.modes()).isEmpty();
    }

    @Test
    void declaredApiOverridesDerivedReads() {
        // A SaaS connector registers batch/stream reads but is semantically an API pull.
        ModeResolution resolution = ModeResolver.resolve(
                EnumSet.of(DerivedCapability.BATCH_READ, DerivedCapability.STREAM_READ),
                List.of("api"));

        assertThat(resolution.modes()).containsExactly(SourceMode.API);
        assertThat(resolution.source(SourceMode.API)).isEqualTo(ModeSource.DECLARED);
    }

    @Test
    void multipleDeclaredModesAreAllMarkedDeclared() {
        ModeResolution resolution = ModeResolver.resolve(
                EnumSet.of(DerivedCapability.BATCH_READ, DerivedCapability.STREAM_READ),
                List.of("snapshot", "cdc"));

        assertThat(resolution.modes()).containsExactlyInAnyOrder(SourceMode.SNAPSHOT, SourceMode.CDC);
        assertThat(resolution.source(SourceMode.SNAPSHOT)).isEqualTo(ModeSource.DECLARED);
        assertThat(resolution.source(SourceMode.CDC)).isEqualTo(ModeSource.DECLARED);
    }

    @Test
    void unknownDeclaredModeIsRejectedRatherThanSilentlyDropped() {
        assertThatThrownBy(() -> ModeResolver.resolve(
                EnumSet.of(DerivedCapability.BATCH_READ), List.of("steam")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("steam");
    }
}
