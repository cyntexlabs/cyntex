package io.cyntex.core.model;

/**
 * Pipeline read mode: what a pipeline consumes along the snapshot × change-tail axis. It is a
 * pipeline-level setting, not a source property — one shared source may be read three different ways
 * by three pipelines. It only distinguishes behaviour for {@code cdc} sources; for bounded
 * ({@code snapshot}/{@code file}/{@code api}) and pure-stream sources it is a no-op, except that
 * {@code snapshot_only} over a pure {@code stream} source is a validate-layer error.
 */
@Doc("Pipeline read mode: what a pipeline consumes from a cdc source, along snapshot x change-tail.")
public enum ReadMode {
    @Doc("Full initial snapshot, then continuously apply the incremental change tail (the default).")
    SNAPSHOT_AND_CDC("snapshot_and_cdc"),
    @Doc("Skip the initial snapshot and read only the incremental change tail.")
    CDC_ONLY("cdc_only"),
    @Doc("Read the current rows once as a bounded pass and stop; no incremental tail.")
    SNAPSHOT_ONLY("snapshot_only");

    private final String yaml;

    ReadMode(String yaml) {
        this.yaml = yaml;
    }

    public String yaml() {
        return yaml;
    }
}
