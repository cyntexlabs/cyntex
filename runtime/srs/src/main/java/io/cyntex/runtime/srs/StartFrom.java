package io.cyntex.runtime.srs;

import io.cyntex.core.common.CyntexException;

import java.io.Serializable;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/**
 * Where a pipeline starts consuming a mining chain's incremental tail — the typed reading of its
 * {@code start_from} setting. Three forms: {@link Earliest} replays every change still buffered,
 * {@link Latest} takes only changes from now on, and {@link At} starts from the first change at or after
 * an instant. It positions this one pipeline's consumer cursor into the change ring; it never moves the
 * shared mining chain's own read offset.
 *
 * <p>The authoring layer holds {@code start_from} as a free string and does not constrain its format, so
 * an unrecognized value is caught here at consumption time rather than by the validate layer.
 *
 * <p>It is {@link Serializable}: parsed once at pipeline assembly, it is captured by the Jet source's
 * create function and shipped to the member that resolves it against the ring there.
 */
public sealed interface StartFrom extends Serializable permits StartFrom.Earliest, StartFrom.Latest, StartFrom.At {

    /** Start from the oldest change still in the ring — replay everything currently buffered. */
    record Earliest() implements StartFrom {
    }

    /** Start after the newest change — take only changes appended from now on. */
    record Latest() implements StartFrom {
    }

    /** Start from the first change whose event time is at or after {@code instant}. */
    record At(Instant instant) implements StartFrom {
        public At {
            Objects.requireNonNull(instant, "instant");
        }
    }

    static StartFrom earliest() {
        return new Earliest();
    }

    static StartFrom latest() {
        return new Latest();
    }

    static StartFrom at(Instant instant) {
        return new At(instant);
    }

    /**
     * Parses a {@code start_from} value: the keyword {@code earliest} or {@code latest}, or an ISO-8601
     * instant. A value that is neither keyword nor a parseable instant is rejected.
     */
    static StartFrom parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        switch (raw) {
            case "earliest":
                return earliest();
            case "latest":
                return latest();
            default:
                try {
                    return at(Instant.parse(raw));
                } catch (DateTimeParseException e) {
                    throw new CyntexException(CaptureError.START_FROM_UNPARSABLE, Map.of("value", raw), e);
                }
        }
    }
}
