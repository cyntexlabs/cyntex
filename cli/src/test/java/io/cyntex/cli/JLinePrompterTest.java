package io.cyntex.cli;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The block-capture logic behind {@link JLinePrompter}'s multi-line {@code lines()} primitive, driven
 * by a fake line source so the sentinel / end-of-input / indentation rules are exercised directly. The
 * thin JLine adapter that turns a line reader into this source is not unit-tested (a real terminal is
 * needed); the rules that matter live in {@code captureBlock}.
 */
class JLinePrompterTest {

    /** A line source that yields each line once, then {@code null} forever (end of input). */
    private static Supplier<String> source(String... lines) {
        Deque<String> queue = new ArrayDeque<>(List.of(lines));
        return () -> queue.isEmpty() ? null : queue.poll();
    }

    @Test
    void capturesAMultilineBlockUntilTheDotSentinel() {
        // the block ends at the lone '.'; lines after it are not consumed into the block
        assertThat(JLinePrompter.captureBlock(source("SELECT 1", "FROM t", ".", "ignored")))
                .isEqualTo("SELECT 1\nFROM t");
    }

    @Test
    void preservesLeadingIndentationOfBlockLines() {
        assertThat(JLinePrompter.captureBlock(source("def f():", "    return 1", ".")))
                .isEqualTo("def f():\n    return 1");
    }

    @Test
    void endsTheBlockAtEndOfInputWithoutASentinel() {
        assertThat(JLinePrompter.captureBlock(source("only line"))).isEqualTo("only line");
    }

    @Test
    void anImmediateSentinelYieldsAnEmptyBlock() {
        assertThat(JLinePrompter.captureBlock(source("."))).isEmpty();
    }

    @Test
    void immediateEndOfInputYieldsAnEmptyBlock() {
        assertThat(JLinePrompter.captureBlock(source())).isEmpty();
    }

    @Test
    void aBlankLineInsideTheBlockIsPreserved() {
        // only a lone '.' terminates; an empty line is real content (e.g. a paragraph break in SQL)
        assertThat(JLinePrompter.captureBlock(source("a", "", "b", ".")))
                .isEqualTo("a\n\nb");
    }
}
