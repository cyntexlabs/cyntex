package io.cyntex.control.restapi;

import io.cyntex.control.core.CallerOrigin;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Classifies an inbound request's remote address into the single fact the zero-user bootstrap channel
 * turns on: whether it arrived over the loopback interface. The control layer stays framework-free and
 * never inspects a network address itself; this presentation-side helper reads the address and passes a
 * {@link CallerOrigin} in.
 *
 * <p>Fail-secure: only an address that parses and is confirmed loopback yields {@code LOOPBACK}; an
 * absent, blank or unparseable address is {@code REMOTE}. The bootstrap exception must never widen
 * because an address could not be read. The remote address is the numeric peer address the servlet
 * container reports, so parsing it never triggers a name lookup.
 */
final class CallerOrigins {

    private CallerOrigins() {
    }

    /** LOOPBACK when {@code remoteAddr} is a loopback address, REMOTE for anything else (fail-secure). */
    static CallerOrigin classify(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return CallerOrigin.REMOTE;
        }
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress()
                    ? CallerOrigin.LOOPBACK
                    : CallerOrigin.REMOTE;
        } catch (UnknownHostException unparseable) {
            return CallerOrigin.REMOTE;
        }
    }
}
