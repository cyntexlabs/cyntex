package io.cyntex.app;

import com.hazelcast.core.HazelcastException;
import io.cyntex.core.common.CyntexException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Starting the embedded Hazelcast member can fail for operational reasons — most commonly the
 * loopback member port is already held by another server on the same host. That is a user-facing,
 * diagnosable startup failure, so it surfaces as a coded diagnostic (rendered by
 * {@code CodedFailureAnalyzer}) rather than a bare Hazelcast stack trace. A failure that is not
 * Hazelcast's — a programmer error while assembling the member — is not laundered into a code; it
 * crashes bare.
 */
class HazelcastStartupTest {

    @Test
    void memberStartupFailureBecomesACodedDiagnostic() {
        HazelcastException cause = new HazelcastException("Ports [5701-5801] are already in use");

        Throwable thrown = catchThrowable(
                () -> HazelcastConfiguration.startMember(() -> {
                    throw cause;
                }));

        assertThat(thrown).isInstanceOf(CyntexException.class);
        CyntexException coded = (CyntexException) thrown;
        assertThat(coded.code()).isEqualTo(BootError.HAZELCAST_UNAVAILABLE);
        assertThat(coded.getCause()).isSameAs(cause);
    }

    @Test
    void aNonHazelcastFailureIsNotLaunderedIntoACode() {
        RuntimeException bug = new IllegalStateException("invariant violated while building the member");

        assertThatThrownBy(() -> HazelcastConfiguration.startMember(() -> {
            throw bug;
        })).isSameAs(bug);
    }
}
