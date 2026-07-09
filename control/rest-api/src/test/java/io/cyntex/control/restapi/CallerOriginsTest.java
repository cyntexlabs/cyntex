package io.cyntex.control.restapi;

import io.cyntex.control.core.CallerOrigin;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The presentation-side classification of a request's remote address into the one fact the zero-user
 * bootstrap channel turns on — loopback or not. The control layer never inspects an address itself; this
 * helper does, and passes a {@link CallerOrigin} in. It is fail-secure: only an address it can read and
 * confirm is loopback yields LOOPBACK; everything else — a routable address, an absent or unreadable one —
 * is REMOTE, so the bootstrap exception never widens by accident.
 */
class CallerOriginsTest {

    @Test
    void anIpv4LoopbackAddressIsLoopback() {
        assertThat(CallerOrigins.classify("127.0.0.1")).isEqualTo(CallerOrigin.LOOPBACK);
        assertThat(CallerOrigins.classify("127.1.2.3")).isEqualTo(CallerOrigin.LOOPBACK); // all of 127/8 is loopback
    }

    @Test
    void anIpv6LoopbackAddressIsLoopback() {
        assertThat(CallerOrigins.classify("::1")).isEqualTo(CallerOrigin.LOOPBACK);
        assertThat(CallerOrigins.classify("0:0:0:0:0:0:0:1")).isEqualTo(CallerOrigin.LOOPBACK);
    }

    @Test
    void aRoutableAddressIsRemote() {
        assertThat(CallerOrigins.classify("10.0.0.5")).isEqualTo(CallerOrigin.REMOTE);
        assertThat(CallerOrigins.classify("192.168.1.20")).isEqualTo(CallerOrigin.REMOTE);
        assertThat(CallerOrigins.classify("8.8.8.8")).isEqualTo(CallerOrigin.REMOTE);
    }

    @Test
    void anAbsentAddressIsRemoteFailingSecure() {
        assertThat(CallerOrigins.classify(null)).as("null").isEqualTo(CallerOrigin.REMOTE);
        assertThat(CallerOrigins.classify("")).as("empty").isEqualTo(CallerOrigin.REMOTE);
        assertThat(CallerOrigins.classify("   ")).as("blank").isEqualTo(CallerOrigin.REMOTE);
    }

    @Test
    void anUnparseableAddressIsRemoteFailingSecure() {
        // A malformed literal is classified REMOTE (the fail-secure branch), never let through as loopback
        // or left to crash. A colon makes it an IPv6 literal, so it is rejected synchronously with no name
        // lookup — this exercises the catch that guards the whole loopback classification.
        assertThat(CallerOrigins.classify("1:2:3")).as("incomplete IPv6").isEqualTo(CallerOrigin.REMOTE);
        assertThat(CallerOrigins.classify("::g")).as("invalid hex in IPv6").isEqualTo(CallerOrigin.REMOTE);
    }
}
