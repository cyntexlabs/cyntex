package io.cyntex.spi.transform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The transform node kinds are a closed, fixed set. */
class NodeTypeTest {

    @Test
    void nodeTypesAreTheClosedSetOfSix() {
        assertThat(NodeType.values())
                .containsExactly(
                        NodeType.FILTER,
                        NodeType.MAP,
                        NodeType.JS,
                        NodeType.UNION,
                        NodeType.NEST,
                        NodeType.JOIN);
    }
}
