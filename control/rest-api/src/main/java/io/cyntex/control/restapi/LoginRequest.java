package io.cyntex.control.restapi;

/**
 * The login request body: the username and password to verify. A wrapper record rather than bare fields so
 * the request can grow (a requested scope, a client id) without changing the wire shape a client already
 * sends. The server verifies these against the user store; the raw password is used only to verify and is
 * never stored or echoed.
 */
public record LoginRequest(String username, String password) {
}
