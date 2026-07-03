package io.cyntex.core.model;

/**
 * Task-level settings: four cross-cutting fields plus the two-field read axis — {@code readMode} and
 * {@code startFrom} — that say what a pipeline consumes and where it starts. {@code schedule} is only
 * legal for a bounded read; {@code startFrom} only for a read with an incremental tail (validate-layer
 * rules).
 */
@Doc("Task-level settings shared across a task: cross-cutting knobs plus the read axis.")
public record Settings(@Doc(value = "How the task reacts to record-level errors during processing.",
                            def = "fail")
                       ErrorPolicy errorPolicy,
                       @Doc(value = "Number of records processed per batch.", def = "1000")
                       Integer batchSize,
                       @Doc(value = "Number of parallel workers used to process the task.", def = "1")
                       Integer parallelism,
                       @Doc("Cron-style schedule for running the task; only valid for a bounded read.")
                       String schedule,
                       @Doc(value = "What the pipeline reads from a cdc source: full snapshot then "
                               + "changes, changes only, or a one-shot snapshot.", def = "snapshot_and_cdc")
                       ReadMode readMode,
                       @Doc(value = "Where to start consuming an incremental tail: earliest, latest, "
                               + "or an ISO-8601 timestamp.", def = "latest")
                       String startFrom) {
}
