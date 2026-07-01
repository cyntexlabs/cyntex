package io.cyntex.core.common;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The authoritative registry of first-party error-code domains (ADR-0024 D2). The {@code <domain>}
 * segment of every canonical code must be one of these — the build-time format gate (ADR-0024 D5-2)
 * rejects any code whose domain is unregistered. This closes the legacy class of bug where a typo
 * (e.g. {@code dls.} for {@code dsl.}) silently minted a brand-new namespace.
 *
 * <p>An enum (not a free-text file) keeps the registry native-clean and compile-checked: zero
 * runtime reflection, zero I/O. Adding a domain = adding a constant here + review.
 */
public enum Domain {
    DSL,
    CLI,
    CORE,
    CATALOG,
    SCHEMA,
    // core ring: pipeline lifecycle state machine (illegal transitions)
    LIFECYCLE,
    // service assembly root: role selection and startup-fatal failures (app)
    ROLE,
    BOOT;

    /** The lower-case identifier used as the {@code <domain>} segment of a canonical code. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether {@code domain} is a registered domain id (exact, case-sensitive). */
    public static boolean isRegistered(String domain) {
        for (Domain d : values()) {
            if (d.id().equals(domain)) {
                return true;
            }
        }
        return false;
    }

    /** All registered domain ids. */
    public static Set<String> ids() {
        Set<String> ids = new TreeSet<>();
        for (Domain d : values()) {
            ids.add(d.id());
        }
        return ids;
    }
}
