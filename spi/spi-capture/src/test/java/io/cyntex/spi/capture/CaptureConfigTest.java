package io.cyntex.spi.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaptureConfigTest {

    @Test
    void connectorIdIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CaptureConfig(null, Map.of(), List.of()));
    }

    @Test
    void settingsAndStreamsDefaultToEmptyWhenNull() {
        CaptureConfig config = new CaptureConfig("mysql", null, null);

        assertThat(config.settings()).isEmpty();
        assertThat(config.streams()).isEmpty();
    }

    @Test
    void settingsAreADefensiveCopy() {
        Map<String, Object> source = new HashMap<>();
        source.put("host", "localhost");
        CaptureConfig config = new CaptureConfig("mysql", source, List.of());

        source.put("host", "elsewhere");

        assertThat(config.settings()).containsEntry("host", "localhost");
    }

    @Test
    void settingsAreUnmodifiable() {
        CaptureConfig config = new CaptureConfig("mysql", Map.of("host", "localhost"), List.of());

        assertThatThrownBy(() -> config.settings().put("port", 3306))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void streamsAreADefensiveCopyAndUnmodifiable() {
        List<String> source = new ArrayList<>(List.of("orders"));
        CaptureConfig config = new CaptureConfig("mysql", Map.of(), source);

        source.add("customers");

        assertThat(config.streams()).containsExactly("orders");
        assertThatThrownBy(() -> config.streams().add("customers"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
