package io.cyntex.control.restapi;

/**
 * The zero-user bootstrap request body: the username and password for the first admin to create. A wrapper
 * record so the request can grow without changing the wire shape. The caller's origin is not part of the
 * body — it is the request's own remote address, classified server-side — so the loopback guard cannot be
 * spoofed through the payload.
 */
public record BootstrapRequest(String username, String password) {
}
