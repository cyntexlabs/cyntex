package io.cyntex.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The connector-capability value: the raw ids a connector's registerCapabilities declares, normalized
 * to a sorted, unmodifiable, defensively copied set — null in becomes empty out.
 */
class ConnectorCapabilitiesTest {

    @Test
    void carriesTheRegisteredCapabilityIds() {
        ConnectorCapabilities caps =
                new ConnectorCapabilities(Set.of("batch_read_function", "write_record_function"));

        assertThat(caps.capabilityIds())
                .containsExactly("batch_read_function", "write_record_function");
    }

    @Test
    void normalizesANullIdSetToEmpty() {
        assertThat(new ConnectorCapabilities(null).capabilityIds()).isEmpty();
    }

    @Test
    void holdsTheIdsSortedForDeterministicIteration() {
        ConnectorCapabilities caps = new ConnectorCapabilities(
                new LinkedHashSet<>(List.of("write_record_function", "batch_read_function")));

        assertThat(caps.capabilityIds())
                .containsExactly("batch_read_function", "write_record_function");
    }

    @Test
    void isAnUnmodifiableDefensiveCopy() {
        Set<String> source = new LinkedHashSet<>();
        source.add("batch_read_function");
        ConnectorCapabilities caps = new ConnectorCapabilities(source);

        source.add("write_record_function"); // mutating the source must not leak into the value

        assertThat(caps.capabilityIds()).containsExactly("batch_read_function");
        assertThatThrownBy(() -> caps.capabilityIds().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
