package io.cyntex.spi.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/**
 * The opaque source position: a connector-defined point in a source's change stream, carried as an
 * opaque token. It is the cdc-start position recorded when a snapshot hands off to the change tail
 * and the point a cdc stream resumes from; cyntex never interprets the token, only stores and
 * threads it back. Two positions with the same token are equal (value semantics).
 */
class SourcePositionTest {

    @Test
    void carriesItsOpaqueTokenVerbatim() {
        SourcePosition position = new SourcePosition("binlog.000042:1234");

        assertThat(position.token()).isEqualTo("binlog.000042:1234");
    }

    @Test
    void twoPositionsWithTheSameTokenAreEqual() {
        assertThat(new SourcePosition("gtid:9-17")).isEqualTo(new SourcePosition("gtid:9-17"));
    }

    @Test
    void aNullTokenIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new SourcePosition(null));
    }
}
