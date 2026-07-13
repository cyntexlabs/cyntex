package io.cyntex.spi.capture;

import java.util.Objects;

/**
 * An opaque position in a source's change stream, carried as a connector-defined token. It marks the
 * cdc-start position recorded when a snapshot phase hands off to the change tail, and the point a cdc
 * stream resumes from after a restart. The token is opaque: cyntex never parses it, only stores it
 * and threads it back to the connector. The token string — never a connector object — is what crosses
 * a persistence or serialization boundary. An immutable value; two positions with the same token are
 * equal.
 *
 * <p>Exactly how a snapshot and its change tail are stitched without gap or overlap is a source-side
 * concern layered on top of this token, not fixed by this type.
 */
public record SourcePosition(String token) {

    public SourcePosition {
        Objects.requireNonNull(token, "token");
    }
}
