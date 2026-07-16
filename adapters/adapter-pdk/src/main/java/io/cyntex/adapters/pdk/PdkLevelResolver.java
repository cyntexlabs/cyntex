package io.cyntex.adapters.pdk;

import java.util.OptionalInt;

/**
 * Judges whether a connector may be loaded by resolving its declared PDK API-level requirement
 * against the level this bridge provides. A pure four-state decision: compatible, incompatible, a
 * version the registry does not recognize, or nothing declared. It only judges — refusing a connector
 * and rendering the coded, actionable diagnosis is the load path's job; this resolver stays free of
 * the error-code system so it is a plain, testable function.
 *
 * <p>A connector expresses its requirement as a PDK API version and/or an already-derived level, the
 * two reserved slots a catalog entry carries. An explicit level is authoritative and wins over the
 * version; otherwise the version resolves to a level through the Cyntex-side registry, and a version
 * with no row resolves to {@link LevelOutcome#UNKNOWN_VERSION} rather than crashing — operator data
 * gets a verdict, not a bare stack. Comparison is {@code required <= provided} — a newer requirement
 * is refused, never silently downgraded.
 */
public final class PdkLevelResolver {

    private PdkLevelResolver() {
    }

    /**
     * Resolves a connector's declared requirement against {@link PdkApiLevels#ENGINE_LEVEL}.
     *
     * @param pdkApiVersion the declared PDK API version, or {@code null}/blank if none
     * @param requiredLevel the declared, already-derived level as a string, or {@code null}/blank if
     *     none; takes precedence over {@code pdkApiVersion}
     * @throws IllegalArgumentException if {@code requiredLevel} is present but not an integer — a
     *     malformed already-derived level is a producer defect, not operator-recoverable input
     */
    public static LevelResolution resolve(String pdkApiVersion, String requiredLevel) {
        int engine = PdkApiLevels.ENGINE_LEVEL;

        if (!isBlank(requiredLevel)) {
            return judge(parseLevel(requiredLevel), engine);
        }
        if (!isBlank(pdkApiVersion)) {
            OptionalInt required = PdkApiLevels.levelOf(pdkApiVersion.trim());
            return required.isPresent()
                    ? judge(required.getAsInt(), engine)
                    : LevelResolution.unknownVersion(engine);
        }
        return LevelResolution.undeclared(engine);
    }

    private static LevelResolution judge(int required, int engine) {
        return required <= engine
                ? LevelResolution.compatible(required, engine)
                : LevelResolution.incompatible(required, engine);
    }

    private static int parseLevel(String requiredLevel) {
        try {
            return Integer.parseInt(requiredLevel.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "declared required-level is not an integer: " + requiredLevel, e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
