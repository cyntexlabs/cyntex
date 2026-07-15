package io.cyntex.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The file endpoint driver, checked against files alone - no product, no connector.
 *
 * <p>This is the harness's independent reading of a target, so it is checked the way the product
 * never gets to define: by writing bytes with this driver and reading them back as text, and by
 * reading text this driver did not write. If this driver and the connector shared any code, a
 * specification's count would agree with the connector by construction and would keep agreeing while
 * nothing crossed the product at all.
 */
class FileEndpointsTest {

    @TempDir
    private Path directory;

    private final FileEndpoints endpoints = new FileEndpoints();

    @Test
    void seedingWritesAHeaderAndTheRowsNumberedFromOne() throws IOException {
        endpoints.seed(directory.toString(), "orders", 3);

        assertThat(lines("orders")).containsExactly("id,seq", "1,1", "2,2", "3,3");
    }

    @Test
    void seedingReplacesWhateverTheTableHeld() throws IOException {
        endpoints.seed(directory.toString(), "orders", 5);
        endpoints.seed(directory.toString(), "orders", 2);

        assertThat(lines("orders")).containsExactly("id,seq", "1,1", "2,2");
    }

    @Test
    void countingReadsTheRowsBackWithoutTheHeader() {
        endpoints.seed(directory.toString(), "orders", 3);

        assertThat(endpoints.count(directory.toString(), "orders")).isEqualTo(3L);
    }

    /**
     * The reading that makes an {@code await} on a target meaningful: before the product writes it, the
     * target table does not exist, and the honest count of a table that is not there is zero. Reporting
     * it as an error instead would turn every wait for a first write into a failure.
     */
    @Test
    void aTableNoOneHasWrittenYetCountsZero() {
        assertThat(endpoints.count(directory.toString(), "never_written")).isZero();
    }

    @Test
    void countingReadsRowsThisDriverDidNotWrite() throws IOException {
        Files.writeString(directory.resolve("orders.csv"), "id,seq\n7,7\n8,8\n");

        assertThat(endpoints.count(directory.toString(), "orders")).isEqualTo(2L);
    }

    @Test
    void insertingAppendsRowsAfterTheHighestIdTheTableHolds() throws IOException {
        endpoints.seed(directory.toString(), "orders", 3);

        endpoints.cdc(directory.toString(), "orders", CdcOp.INSERT, 2);

        assertThat(lines("orders")).containsExactly("id,seq", "1,1", "2,2", "3,3", "4,4", "5,5");
    }

    @Test
    void deletingRemovesTheLowestIdsAndLowersTheCount() throws IOException {
        endpoints.seed(directory.toString(), "orders", 4);

        endpoints.cdc(directory.toString(), "orders", CdcOp.DELETE, 2);

        assertThat(lines("orders")).containsExactly("id,seq", "3,3", "4,4");
        assertThat(endpoints.count(directory.toString(), "orders")).isEqualTo(2L);
    }

    /** An update changes rows without adding or removing any, so a count cannot witness it. */
    @Test
    void updatingRewritesTheSequenceOfTheLowestIdsAndLeavesTheCountAlone() throws IOException {
        endpoints.seed(directory.toString(), "orders", 3);

        endpoints.cdc(directory.toString(), "orders", CdcOp.UPDATE, 2);

        assertThat(lines("orders")).containsExactly("id,seq", "1,-1", "2,-2", "3,3");
        assertThat(endpoints.count(directory.toString(), "orders")).isEqualTo(3L);
    }

    /**
     * Changing a table that was never seeded is an authoring mistake, not a change: the specification
     * says produce changes "against a table that is already seeded", and silently creating one here
     * would let a specification whose seed and cdc name different tables pass by accident.
     */
    @Test
    void changingATableNoOneSeededRefusesRatherThanCreatingIt() {
        assertThatThrownBy(() -> endpoints.cdc(directory.toString(), "orders", CdcOp.INSERT, 1))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("orders")
                .hasMessageContaining("not been seeded");
    }

    @Test
    void anEndpointNamingSomethingThatIsNotADirectoryRefuses() {
        assertThatThrownBy(() -> endpoints.count(directory.resolve("absent").toString(), "orders"))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("is not a directory");
    }

    private List<String> lines(String table) throws IOException {
        return Files.readAllLines(directory.resolve(table + ".csv"));
    }
}
