package io.cyntex.adapters.pdk;

/**
 * The result of resolving a connector's declared PDK API-level requirement against the level this
 * bridge provides. Carries the verdict plus the two levels it was reached from, so the load path can
 * name the required and provided levels in a coded, actionable diagnosis when it refuses to load.
 *
 * <p>{@code requiredLevel} is {@code null} exactly when the outcome is {@link LevelOutcome#UNDECLARED}
 * (there was no requirement to resolve); it is the derived level in every other case, including the
 * incompatible one.
 */
public record LevelResolution(LevelOutcome outcome, Integer requiredLevel, int engineLevel) {

    /** Whether the connector may be loaded — true only for {@link LevelOutcome#COMPATIBLE}. */
    public boolean compatible() {
        return outcome == LevelOutcome.COMPATIBLE;
    }

    static LevelResolution compatible(int requiredLevel, int engineLevel) {
        return new LevelResolution(LevelOutcome.COMPATIBLE, requiredLevel, engineLevel);
    }

    static LevelResolution incompatible(int requiredLevel, int engineLevel) {
        return new LevelResolution(LevelOutcome.INCOMPATIBLE, requiredLevel, engineLevel);
    }

    static LevelResolution undeclared(int engineLevel) {
        return new LevelResolution(LevelOutcome.UNDECLARED, null, engineLevel);
    }
}
