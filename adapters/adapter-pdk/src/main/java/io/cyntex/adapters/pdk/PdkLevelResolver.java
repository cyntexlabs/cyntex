package io.cyntex.adapters.pdk;

/**
 * Judges whether a connector may be loaded by resolving its declared PDK API-level requirement
 * against the level this bridge provides. A pure three-state decision: compatible, incompatible, or
 * nothing declared. It only judges — refusing an incompatible connector and rendering the coded,
 * actionable diagnosis is the load path's job; this resolver stays free of the error-code system so
 * it is a plain, testable function.
 *
 * <p>A connector expresses its requirement as a PDK API version and/or an already-derived level, the
 * two reserved slots a catalog entry carries. An explicit level is authoritative and wins over the
 * version; otherwise the version resolves to a level through the Cyntex-side registry. Comparison is
 * {@code required <= provided} — a newer requirement is refused, never silently downgraded.
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
     * @throws IllegalArgumentException if {@code requiredLevel} is present but not an integer
     * @throws IllegalStateException if only a version is declared and it has no registered level
     */
    public static LevelResolution resolve(String pdkApiVersion, String requiredLevel) {
        int engine = PdkApiLevels.ENGINE_LEVEL;

        Integer required = derive(pdkApiVersion, requiredLevel);
        if (required == null) {
            return LevelResolution.undeclared(engine);
        }
        return required <= engine
                ? LevelResolution.compatible(required, engine)
                : LevelResolution.incompatible(required, engine);
    }

    /** The effective required level, or {@code null} if nothing was declared. */
    private static Integer derive(String pdkApiVersion, String requiredLevel) {
        if (!isBlank(requiredLevel)) {
            try {
                return Integer.valueOf(requiredLevel.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "declared required-level is not an integer: " + requiredLevel, e);
            }
        }
        if (!isBlank(pdkApiVersion)) {
            return PdkApiLevels.level(pdkApiVersion.trim());
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
