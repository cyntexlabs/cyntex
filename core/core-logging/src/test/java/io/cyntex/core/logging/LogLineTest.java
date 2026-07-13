package io.cyntex.core.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class LogLineTest {

    @Test
    void holdsTheTimestampLevelAndMessage() {
        LogLine line = new LogLine(1_700_000_000_000L, "INFO", "converged orders_sync to RUNNING");

        assertThat(line.timestampMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.message()).isEqualTo("converged orders_sync to RUNNING");
    }

    @Test
    void requiresLevelAndMessage() {
        assertThatNullPointerException().isThrownBy(() -> new LogLine(0L, null, "m"));
        assertThatNullPointerException().isThrownBy(() -> new LogLine(0L, "INFO", null));
    }
}
