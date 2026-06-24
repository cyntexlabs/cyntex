package io.cyntex.core.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CyntexExceptionTest {

    /** A throwaway code purely for exercising the carrier; never scanned (test scope). */
    private enum SampleError implements CyntexErrorCode {
        ALPHA("core.alpha", Severity.ERROR, Set.of("a", "b")),
        BARE("core.bare", Severity.WARNING, Set.of());

        private final String code;
        private final Severity severity;
        private final Set<String> placeholders;

        SampleError(String code, Severity severity, Set<String> placeholders) {
            this.code = code;
            this.severity = severity;
            this.placeholders = placeholders;
        }

        @Override public String code() { return code; }
        @Override public Severity severity() { return severity; }
        @Override public Set<String> placeholders() { return placeholders; }
    }

    @Test
    void exposesItsCodeAndSeverity() {
        CyntexException ex = new CyntexException(SampleError.ALPHA, Map.of("a", 1), null);
        assertThat(ex.code()).isSameAs(SampleError.ALPHA);
        assertThat(ex.code().severity()).isEqualTo(Severity.ERROR);
    }

    @Test
    void getMessageIsDeterministicWithArgsSortedByKey() {
        // Insertion order deliberately reversed to prove the dev string sorts by key.
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("b", 2);
        args.put("a", 1);
        CyntexException ex = new CyntexException(SampleError.ALPHA, args, null);
        assertThat(ex.getMessage()).isEqualTo("core.alpha {a=1, b=2}");
    }

    @Test
    void getMessageIsBareCodeWhenNoArgs() {
        CyntexException ex = new CyntexException(SampleError.BARE, Map.of(), null);
        assertThat(ex.getMessage()).isEqualTo("core.bare");
    }

    @Test
    void nullArgsIsTreatedAsEmpty() {
        CyntexException ex = new CyntexException(SampleError.BARE, null, null);
        assertThat(ex.args()).isEmpty();
        assertThat(ex.getMessage()).isEqualTo("core.bare");
    }

    @Test
    void argsAreUnmodifiableSnapshotOfTheInput() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("a", 1);
        CyntexException ex = new CyntexException(SampleError.ALPHA, args, null);
        // mutating the source map after construction must not leak into the exception
        args.put("a", 999);
        assertThat(ex.args()).containsExactly(Map.entry("a", 1));
        assertThatThrownBy(() -> ex.args().put("x", 0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void causeIsPropagated() {
        Throwable boom = new IllegalStateException("boom");
        CyntexException ex = new CyntexException(SampleError.BARE, Map.of(), boom);
        assertThat(ex.getCause()).isSameAs(boom);
    }
}
