/**
 * Cyntex resource model (source / pipeline / transform / view / serve ...) and
 * canonical-form serialization.
 *
 * <p>This package is the semantic convergence point of the dependency graph: more modules
 * depend on it than on anything else, so it must stay the most stable. Field evolution
 * follows the strict-schema contract: unknown fields are rejected, experimental additions
 * live in a dedicated {@code experimental} section, and the canonical form is a long-term
 * compatibility promise (changing its rules requires updating the golden files and review).
 */
package io.cyntex.core.model;
