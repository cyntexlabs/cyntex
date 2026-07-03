package io.cyntex.spi.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class WriteResultTest {

    @Test
    void carriesTheCountWritten() {
        assertThat(new WriteResult(5L).written()).isEqualTo(5L);
    }

    @Test
    void zeroIsAValidCount() {
        assertThat(new WriteResult(0L).written()).isZero();
    }

    @Test
    void aNegativeCountIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(-1L));
    }
}
