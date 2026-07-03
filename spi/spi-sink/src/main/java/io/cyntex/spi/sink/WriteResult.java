package io.cyntex.spi.sink;

/**
 * The outcome of a batch write: how many events from the batch the target accepted. An immutable
 * value.
 *
 * <p>{@code written} counts the events the target accepted for this batch — an event count, not a
 * count of rows physically changed (an upsert that overwrites an identical row still counts the
 * event). It is never negative.
 */
public record WriteResult(long written) {

    public WriteResult {
        if (written < 0) {
            throw new IllegalArgumentException("written must be non-negative: " + written);
        }
    }
}
