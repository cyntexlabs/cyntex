package io.cyntex.control.restapi;

/**
 * The login response body: the signed session token to present on later requests as
 * {@code Authorization: Bearer <token>}. A record so the shape is explicit and can grow (an expiry hint,
 * the granted scope) without changing the field a client already reads.
 */
public record LoginResponse(String token) {
}
