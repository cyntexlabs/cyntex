package io.cyntex.tools.catalog.assembler;

/**
 * A module the walker deliberately did not ingest, or a non-canonical spec it set aside — recorded
 * (never silently dropped) so the ingest report can account for every directory it saw.
 */
record Exemption(Category category, String module, String detail) {

    /** Why a module or spec was set aside. */
    enum Category {
        /** A known non-publishable module (test, mock, demo, shared library). */
        EXCLUDED,
        /** A spec file in a multi-spec module that is not the canonical one. */
        MULTI_SPEC,
        /** A module that looks like a connector but exposes no resolvable canonical spec. */
        NO_CANONICAL_SPEC,
        /** A canonical spec that parsed but declared no {@code properties.id}. */
        MISSING_ID
    }
}
