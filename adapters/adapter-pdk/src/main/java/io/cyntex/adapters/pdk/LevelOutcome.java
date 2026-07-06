package io.cyntex.adapters.pdk;

/**
 * The verdict of judging a connector's declared PDK API-level requirement against the level this
 * bridge provides. A closed set of three: the connector fits, it needs a newer API than the bridge
 * provides, or it declared no requirement at all.
 */
public enum LevelOutcome {

    /** The declared requirement is at or below the level the bridge provides — safe to load. */
    COMPATIBLE,

    /** The declared requirement is above the level the bridge provides — must be refused, not downgraded. */
    INCOMPATIBLE,

    /** The connector declared neither an API version nor a required level — nothing to judge against. */
    UNDECLARED
}
