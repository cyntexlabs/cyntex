package io.cyntex.control.core;

/**
 * Where a call reached the control layer from, reduced to the single fact the zero-user bootstrap
 * exception turns on: whether it came in over the loopback interface. The control layer stays
 * framework-free (rule R5) and never inspects a network address itself — the surface that receives the
 * request classifies its origin and passes this in.
 */
public enum CallerOrigin {

    /** The call arrived over the loopback interface (a local operator on the server host). */
    LOOPBACK,

    /** The call arrived from any non-loopback address. */
    REMOTE
}
