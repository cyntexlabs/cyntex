package io.cyntex.adapters.pdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PDK API-level resolve contract: judge a connector's declared level requirement against the
 * level this bridge provides, as a pure three-state decision. It only judges and names the levels;
 * turning an incompatible verdict into a refuse-to-load coded error is the load path's job (the
 * connector error domain), so this stays a dependency-free pure function testable in isolation.
 */
class PdkLevelResolverTest {

    private static final int ENGINE = PdkApiLevels.ENGINE_LEVEL;

    // ---- three states ----

    @Test
    void compatibleWhenDeclaredLevelIsAtOrBelowEngine() {
        LevelResolution r = PdkLevelResolver.resolve(null, String.valueOf(ENGINE));
        assertThat(r.outcome()).isEqualTo(LevelOutcome.COMPATIBLE);
        assertThat(r.compatible()).isTrue();
        assertThat(r.requiredLevel()).isEqualTo(ENGINE);
        assertThat(r.engineLevel()).isEqualTo(ENGINE);
    }

    @Test
    void compatibleWhenDeclaredByFrozenBaselineVersion() {
        LevelResolution r = PdkLevelResolver.resolve("2.0.8", null);
        assertThat(r.outcome()).isEqualTo(LevelOutcome.COMPATIBLE);
        assertThat(r.requiredLevel()).isEqualTo(ENGINE);
    }

    @Test
    void incompatibleNamesTheRequiredLevelWhenAboveEngine() {
        int tooNew = ENGINE + 1;
        LevelResolution r = PdkLevelResolver.resolve(null, String.valueOf(tooNew));
        assertThat(r.outcome()).isEqualTo(LevelOutcome.INCOMPATIBLE);
        assertThat(r.compatible()).isFalse();
        // The verdict carries the required level so the load path can name it in a coded diagnosis.
        assertThat(r.requiredLevel()).isEqualTo(tooNew);
        assertThat(r.engineLevel()).isEqualTo(ENGINE);
    }

    @Test
    void undeclaredWhenNeitherVersionNorLevelIsGiven() {
        LevelResolution r = PdkLevelResolver.resolve(null, null);
        assertThat(r.outcome()).isEqualTo(LevelOutcome.UNDECLARED);
        assertThat(r.requiredLevel()).isNull();
        assertThat(r.engineLevel()).isEqualTo(ENGINE);
    }

    @Test
    void blankDeclarationsAreTreatedAsUndeclared() {
        assertThat(PdkLevelResolver.resolve("  ", "").outcome()).isEqualTo(LevelOutcome.UNDECLARED);
    }

    // ---- build-number drift within one base version is API-equivalent ----

    @Test
    void snapshotAndTimestampedBuildsResolveToTheBaselineLevel() {
        assertThat(PdkLevelResolver.resolve("2.0.8-SNAPSHOT", null).outcome())
                .isEqualTo(LevelOutcome.COMPATIBLE);
        assertThat(PdkLevelResolver.resolve("2.0.8-20260609.043233-3", null).outcome())
                .isEqualTo(LevelOutcome.COMPATIBLE);
    }

    @Test
    void baseVersionStripsTheSnapshotAndTimestampedQualifier() {
        assertThat(PdkApiLevels.baseVersion("2.0.8")).isEqualTo("2.0.8");
        assertThat(PdkApiLevels.baseVersion("2.0.8-SNAPSHOT")).isEqualTo("2.0.8");
        assertThat(PdkApiLevels.baseVersion("2.0.8-20260609.043233-3")).isEqualTo("2.0.8");
    }

    // ---- precedence: an explicit level wins over the version ----

    @Test
    void explicitLevelTakesPrecedenceOverVersion() {
        int tooNew = ENGINE + 1;
        // Version alone would be compatible; the explicit derived level is authoritative and wins.
        LevelResolution r = PdkLevelResolver.resolve("2.0.8", String.valueOf(tooNew));
        assertThat(r.outcome()).isEqualTo(LevelOutcome.INCOMPATIBLE);
        assertThat(r.requiredLevel()).isEqualTo(tooNew);
    }

    // ---- registry gaps and malformed input are bare-crash programmer/ops errors, never a verdict ----

    @Test
    void unmappedDeclaredVersionIsARegistryGapNotAVerdict() {
        // A version the Cyntex-side table has no row for cannot be judged; that is an ops gap
        // (add a row), a bare crash — not a silent compatible/incompatible guess.
        assertThatThrownBy(() -> PdkLevelResolver.resolve("9.9.9", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("9.9.9");
    }

    @Test
    void malformedDeclaredLevelIsAProgrammerErrorNotAVerdict() {
        assertThatThrownBy(() -> PdkLevelResolver.resolve(null, "not-an-int"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
