package io.cyntex.e2e;

import java.net.URI;

/**
 * A running product, and the only thing that differs between tiers.
 *
 * <p>Everything a specification does travels over {@link #baseUrl}, so how the server got there -
 * booted in this JVM or launched as the shipped jar - is invisible above this interface. That is the
 * whole point: the fidelity axis lives here and nowhere else, which is what lets one specification
 * run on both tiers rather than two specifications drifting apart.
 */
interface ServerHandle extends AutoCloseable {

    /** Where the product's HTTP surface answers, on loopback. */
    URI baseUrl();

    @Override
    void close();
}
