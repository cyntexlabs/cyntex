package io.cyntex.e2e;

/** Which CI lane a specification belongs to. */
public enum Tier {

    /** Runs on every pull request; must stay minutes-fast. */
    SMOKE,

    /** The full matrix, nightly. */
    FULL,

    /** Performance baselines, on their own lane. */
    PERF
}
