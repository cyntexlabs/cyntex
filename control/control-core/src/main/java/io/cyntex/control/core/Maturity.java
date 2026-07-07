package io.cyntex.control.core;

/**
 * Per-frontend rollout stage at which an operation becomes visible on a face.
 *
 * <p>Declaration order is the rollout order {@code POC < ALPHA < BETA < GA}, so a face surface can be
 * derived as "every operation whose stage on this face is at or below a ceiling" (see
 * {@link OperationRegistry#exposedOn(Frontend, Maturity)}).
 */
public enum Maturity {
    POC,
    ALPHA,
    BETA,
    GA
}
