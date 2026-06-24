package io.cyntex.core.model;

/**
 * Task-level cross-cutting settings, v1 = exactly four fields (ADR-0016 §1, X7).
 * {@code schedule} is only legal for bounded tasks (validate-layer rule).
 */
@Doc("Task-level cross-cutting settings shared across all resources in a task.")
public record Settings(@Doc(value = "How the task reacts to record-level errors during processing.",
                            def = "fail")
                       ErrorPolicy errorPolicy,
                       @Doc(value = "Number of records processed per batch.", def = "1000")
                       Integer batchSize,
                       @Doc(value = "Number of parallel workers used to process the task.", def = "1")
                       Integer parallelism,
                       @Doc("Cron-style schedule for running the task; only valid for bounded tasks.")
                       String schedule) {
}
