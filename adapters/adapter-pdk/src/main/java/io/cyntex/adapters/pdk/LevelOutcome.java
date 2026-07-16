package io.cyntex.adapters.pdk;

/**
 * The verdict of judging a connector's declared PDK API-level requirement against the level this
 * bridge provides. A closed set of four: the connector fits, it needs a newer API than the bridge
 * provides, it declared a version this bridge does not recognize, or it declared no requirement at all.
 */
public enum LevelOutcome {

    /** The declared requirement is at or below the level the bridge provides — safe to load. */
    COMPATIBLE,

    /** The declared requirement is above the level the bridge provides — must be refused, not downgraded. */
    INCOMPATIBLE,

    /**
     * The connector declared a PDK API version the Cyntex-side registry has no row for, so it cannot be
     * placed on the level axis at all — must be refused with a coded diagnosis, never guessed
     * compatible or incompatible.
     */
    UNKNOWN_VERSION,

    /** The connector declared neither an API version nor a required level — nothing to judge against. */
    UNDECLARED
}
